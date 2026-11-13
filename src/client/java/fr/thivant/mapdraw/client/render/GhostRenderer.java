package fr.thivant.mapdraw.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.client.ClientConfig;
import fr.thivant.mapdraw.client.ClientNet;
import fr.thivant.mapdraw.client.LayerManager;
import fr.thivant.mapdraw.client.Terrain;
import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;

/**
 * Affichage des tracés dans le monde sous forme de blocs fantômes translucides.
 * <p>
 * Un mesh par chunk, reconstruit uniquement quand un tracé ou le terrain change (jamais à chaque image).
 * En mode GPU, le mesh est envoyé une fois dans un buffer GPU persistant puis redessiné tel quel.
 */
public final class GhostRenderer implements LayerManager.Listener {
	private static final int REBUILD_BUDGET_PER_TICK = 24;
	private static final int TERRAIN_CHECK_BUDGET_PER_TICK = 1536;
	private static final float INSET = 0.004f;
	/** Hauteur de la plaque des surfaces au-dessus du sol. */
	private static final float SURFACE_HEIGHT = 0.06f;
	/** VERTEX | COPY_DST, comme les buffers de vertex vanilla. */
	private static final int VERTEX_BUFFER_USAGE = 40;

	private static final class ChunkMesh {
		final long key;
		final int originX;
		final int originZ;
		float[] pos = new float[0];
		int[] col = new int[0];
		int vertexCount;
		long[] cells = new long[0];
		int[] ys = new int[0];
		GpuBuffer gpu;
		int indexCount;
		boolean gpuDirty = true;

		ChunkMesh(long key) {
			this.key = key;
			this.originX = CellKey.x(key) << 4;
			this.originZ = CellKey.z(key) << 4;
		}

		void closeGpu() {
			if (gpu != null) {
				try {
					gpu.close();
				} catch (Throwable ignored) {
					// déjà fermé
				}

				gpu = null;
			}

			gpuDirty = true;
		}
	}

	private final LayerManager layers;
	private final Map<Long, ChunkMesh> meshes = new HashMap<>();
	private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
	private String dimension = "";
	private Iterator<Long> watchCursor;
	private boolean gpuFailed;

	public GhostRenderer(LayerManager layers) {
		this.layers = layers;
	}

	// ------------------------------------------------------------------ invalidation

	@Override
	public void chunkChanged(String dim, long chunkKey) {
		if (!dim.equals(dimension)) {
			return;
		}

		int cx = CellKey.x(chunkKey);
		int cz = CellKey.z(chunkKey);
		dirty.add(chunkKey);
		// Les faces de bord dépendent des voisins : on les reconstruit aussi.
		dirty.add(CellKey.chunkKey(cx + 1, cz));
		dirty.add(CellKey.chunkKey(cx - 1, cz));
		dirty.add(CellKey.chunkKey(cx, cz + 1));
		dirty.add(CellKey.chunkKey(cx, cz - 1));
	}

	public void onChunkLoaded(int cx, int cz) {
		long key = CellKey.chunkKey(cx, cz);

		if (!layers.mergedChunkWorld(dimension, key).isEmpty()) {
			dirty.add(key);
		}
	}

	public void onChunkUnloaded(int cx, int cz) {
		ChunkMesh mesh = meshes.remove(CellKey.chunkKey(cx, cz));

		if (mesh != null) {
			mesh.closeGpu();
			watchCursor = null;
		}
	}

	/** Reconstruit tout (changement de dimension, d'opacité…). */
	public void rebuildAll() {
		for (ChunkMesh mesh : meshes.values()) {
			mesh.closeGpu();
		}

		meshes.clear();
		dirty.clear();
		dirty.addAll(layers.visibleChunks(dimension));
		watchCursor = null;
	}

	public void setDimension(String newDimension) {
		if (!newDimension.equals(dimension)) {
			dimension = newDimension;
			rebuildAll();
		}
	}

	public void clear() {
		for (ChunkMesh mesh : meshes.values()) {
			mesh.closeGpu();
		}

		meshes.clear();
		dirty.clear();
		dimension = "";
		watchCursor = null;
	}

	// ------------------------------------------------------------------ tick

	public void tick(ClientLevel level) {
		if (level == null) {
			return;
		}

		// Au moins quelques chunks par tick, puis on continue tant qu'il reste du temps (5 ms max).
		long deadline = System.nanoTime() + 5_000_000L;
		int done = 0;
		Iterator<Long> it = dirty.iterator();

		while (it.hasNext() && (done++ < REBUILD_BUDGET_PER_TICK || System.nanoTime() < deadline)) {
			long key = it.next();
			it.remove();
			rebuild(level, key);
		}

		watchTerrain(level);
	}

	/** Vérifie petit à petit que la hauteur du terrain n'a pas changé sous les tracés. */
	private void watchTerrain(ClientLevel level) {
		if (meshes.isEmpty()) {
			return;
		}

		if (watchCursor == null || !watchCursor.hasNext()) {
			watchCursor = new ArrayList<>(meshes.keySet()).iterator();
		}

		int budget = TERRAIN_CHECK_BUDGET_PER_TICK;

		while (budget > 0 && watchCursor.hasNext()) {
			ChunkMesh mesh = meshes.get(watchCursor.next());

			if (mesh == null || dirty.contains(mesh.key)) {
				continue;
			}

			for (int i = 0; i < mesh.cells.length; i++) {
				long cell = mesh.cells[i];

				if (Terrain.surfaceY(level, CellKey.x(cell), CellKey.z(cell), scratch) != mesh.ys[i]) {
					dirty.add(mesh.key);
					break;
				}
			}

			budget -= Math.max(1, mesh.cells.length);
		}
	}

	private void rebuild(ClientLevel level, long chunkKey) {
		Map<Long, Integer> cells = layers.mergedChunkWorld(dimension, chunkKey);
		ChunkMesh old = meshes.remove(chunkKey);

		if (old != null) {
			old.closeGpu();
			watchCursor = null;
		}

		if (cells.isEmpty() || !level.hasChunk(CellKey.x(chunkKey), CellKey.z(chunkKey))) {
			return;
		}

		ChunkMesh mesh = new ChunkMesh(chunkKey);
		int n = cells.size();
		mesh.cells = new long[n];
		mesh.ys = new int[n];
		Map<Long, Integer> heights = new HashMap<>(n * 2);
		int i = 0;

		for (long cell : cells.keySet()) {
			int y = Terrain.surfaceY(level, CellKey.x(cell), CellKey.z(cell), scratch);
			mesh.cells[i] = cell;
			mesh.ys[i] = y;
			heights.put(cell, y);
			i++;
		}

		// Voisins situés dans les chunks adjacents (pour ne pas dessiner les faces internes).
		NeighborLookup neighbors = new NeighborLookup(level, chunkKey, cells, heights);
		float alpha = ClientConfig.get().opacity;
		int a = Math.round(alpha * 255f) << 24;
		int aSurface = Math.round(ClientConfig.get().surfaceOpacity * 255f) << 24;
		FloatList pos = new FloatList(n * 20 * 3);
		IntList col = new IntList(n * 20);

		for (Map.Entry<Long, Integer> e : cells.entrySet()) {
			long cell = e.getKey();
			int y = heights.get(cell);

			if (y == Terrain.UNKNOWN) {
				continue;
			}

			int wx = CellKey.x(cell);
			int wz = CellKey.z(cell);
			float x0 = (wx - mesh.originX) + INSET;
			float x1 = (wx - mesh.originX) + 1 - INSET;
			float z0 = (wz - mesh.originZ) + INSET;
			float z1 = (wz - mesh.originZ) + 1 - INSET;
			int value = e.getValue();
			int rgb = CellValue.rgb(value);

			if (CellValue.isSurface(value)) {
				// Surface : plaque fine posée sur le sol (teinte au sol, pas de bloc).
				float yp = y + SURFACE_HEIGHT;
				quad(pos, col, aSurface | rgb, x0 - INSET, yp, z0 - INSET, x1 + INSET, yp, z0 - INSET, x1 + INSET, yp, z1 + INSET, x0 - INSET, yp, z1 + INSET);
				continue;
			}

			float y0 = y + INSET;
			float y1 = y + 1 - INSET;
			int top = a | rgb;
			int side = a | shade(rgb, 0.82f);
			int side2 = a | shade(rgb, 0.68f);

			// Dessus
			quad(pos, col, top, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);

			// Nord (z-)
			if (neighbors.heightAt(wx, wz - 1) != y) {
				quad(pos, col, side, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
			}

			// Sud (z+)
			if (neighbors.heightAt(wx, wz + 1) != y) {
				quad(pos, col, side, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1);
			}

			// Ouest (x-)
			if (neighbors.heightAt(wx - 1, wz) != y) {
				quad(pos, col, side2, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
			}

			// Est (x+)
			if (neighbors.heightAt(wx + 1, wz) != y) {
				quad(pos, col, side2, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
			}
		}

		mesh.pos = pos.toArray();
		mesh.col = col.toArray();
		mesh.vertexCount = mesh.col.length;

		if (mesh.vertexCount > 0) {
			meshes.put(chunkKey, mesh);
			watchCursor = null;
		}
	}

	private static int shade(int rgb, float f) {
		int r = Math.round(((rgb >> 16) & 0xFF) * f);
		int g = Math.round(((rgb >> 8) & 0xFF) * f);
		int b = Math.round((rgb & 0xFF) * f);
		return (r << 16) | (g << 8) | b;
	}

	private static void quad(FloatList pos, IntList col, int color,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		pos.add(ax, ay, az);
		pos.add(bx, by, bz);
		pos.add(cx, cy, cz);
		pos.add(dx, dy, dz);
		col.add(color);
		col.add(color);
		col.add(color);
		col.add(color);
	}

	/** Hauteur des blocs fantômes voisins, y compris dans les chunks adjacents. */
	private final class NeighborLookup {
		private final ClientLevel level;
		private final long chunkKey;
		private final Map<Long, Integer> ownCells;
		private final Map<Long, Integer> ownHeights;
		private final Map<Long, Map<Long, Integer>> otherChunks = new HashMap<>();
		private final Map<Long, Integer> otherHeights = new HashMap<>();

		NeighborLookup(ClientLevel level, long chunkKey, Map<Long, Integer> ownCells, Map<Long, Integer> ownHeights) {
			this.level = level;
			this.chunkKey = chunkKey;
			this.ownCells = ownCells;
			this.ownHeights = ownHeights;
		}

		int heightAt(int x, int z) {
			long cell = CellKey.pack(x, z);
			long ck = CellKey.chunkOf(cell);

			// Seuls les blocs de trait (pas les surfaces) masquent les faces de leurs voisins.
			if (ck == chunkKey) {
				Integer v = ownCells.get(cell);
				return v != null && !CellValue.isSurface(v) ? ownHeights.get(cell) : Integer.MAX_VALUE;
			}

			Map<Long, Integer> other = otherChunks.computeIfAbsent(ck, k -> layers.mergedChunkWorld(dimension, k));
			Integer v = other.get(cell);

			if (v == null || CellValue.isSurface(v)) {
				return Integer.MAX_VALUE;
			}

			return otherHeights.computeIfAbsent(cell, k -> Terrain.surfaceY(level, x, z, new BlockPos.MutableBlockPos()));
		}
	}

	// ------------------------------------------------------------------ rendu GPU (par défaut)

	/** Appelé après la passe principale (LevelRenderEvents.AFTER_RENDER_PASS). */
	public void renderGpu(AbstractLevelRenderContext context) {
		ClientConfig cfg = ClientConfig.get();

		if (!cfg.showInWorld || cfg.renderMode != ClientConfig.RenderMode.GPU || meshes.isEmpty()) {
			return;
		}

		try {
			drawGpu(context, cfg);
		} catch (Throwable t) {
			if (!gpuFailed) {
				gpuFailed = true;
				MapDraw.LOGGER.error("Rendu GPU des blocs fantômes impossible, passage en mode compatibilité", t);
				cfg.renderMode = ClientConfig.RenderMode.COMPAT;
				cfg.save();
				ClientNet.chat("MapDraw : rendu GPU indisponible, mode compatibilité activé (voir la config).");
			}
		}
	}

	private void drawGpu(AbstractLevelRenderContext context, ClientConfig cfg) {
		Vec3 cam = context.levelState().cameraRenderState.pos;
		RenderPipeline pipeline = cfg.seeThrough ? GhostPipelines.SEE_THROUGH : GhostPipelines.DEPTH_TESTED;
		GpuDevice device = RenderSystem.getDevice();
		double maxDist = cfg.maxDistance + 12.0;
		double maxDistSq = maxDist * maxDist;
		List<ChunkMesh> visible = new ArrayList<>();
		int maxIndices = 0;

		for (ChunkMesh mesh : meshes.values()) {
			double dx = mesh.originX + 8 - cam.x;
			double dz = mesh.originZ + 8 - cam.z;

			if (dx * dx + dz * dz > maxDistSq) {
				continue;
			}

			if (mesh.gpuDirty || mesh.gpu == null) {
				upload(device, pipeline, mesh);
			}

			if (mesh.gpu != null && mesh.indexCount > 0) {
				visible.add(mesh);
				maxIndices = Math.max(maxIndices, mesh.indexCount);
			}
		}

		if (visible.isEmpty()) {
			return;
		}

		// Tout ce qui écrit dans des buffers est fait AVANT d'ouvrir la passe de rendu.
		RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = sequential.getBuffer(maxIndices);
		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrixCopy());
		Vector4f colorMod = new Vector4f(1f, 1f, 1f, 1f);
		Matrix4f texMatrix = new Matrix4f();
		Vector3f noOffset = new Vector3f();
		GpuBufferSlice[] transforms = new GpuBufferSlice[visible.size()];

		for (int i = 0; i < visible.size(); i++) {
			ChunkMesh mesh = visible.get(i);
			// La translation est intégrée à la matrice (le shader position_color n'utilise pas ModelOffset).
			Matrix4f chunkModelView = new Matrix4f(modelView)
					.translate((float) (mesh.originX - cam.x), (float) (-cam.y), (float) (mesh.originZ - cam.z));
			transforms[i] = RenderSystem.getDynamicUniforms().writeTransform(chunkModelView, colorMod, noOffset, texMatrix);
		}

		RenderTarget target = context.gameRenderer().mainRenderTarget();

		try (RenderPass pass = device.createCommandEncoder().createRenderPass(
				() -> "MapDraw ghost blocks",
				target.getColorTextureView(), Optional.empty(),
				target.hasDepth() ? target.getDepthTextureView() : null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setIndexBuffer(indexBuffer, sequential.type());

			for (int i = 0; i < visible.size(); i++) {
				ChunkMesh mesh = visible.get(i);
				pass.setUniform("DynamicTransforms", transforms[i]);
				pass.setVertexBuffer(0, mesh.gpu.slice());
				pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0);
			}
		}
	}

	private void upload(GpuDevice device, RenderPipeline pipeline, ChunkMesh mesh) {
		mesh.closeGpu();
		mesh.gpuDirty = false;

		if (mesh.vertexCount == 0) {
			return;
		}

		VertexFormat format = pipeline.getVertexFormatBinding(0);

		try (ByteBufferBuilder alloc = new ByteBufferBuilder(mesh.vertexCount * format.getVertexSize())) {
			BufferBuilder builder = new BufferBuilder(alloc, PrimitiveTopology.QUADS, format);
			float[] p = mesh.pos;
			int[] c = mesh.col;

			for (int v = 0; v < mesh.vertexCount; v++) {
				builder.addVertex(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]).setColor(c[v]);
			}

			try (MeshData data = builder.build()) {
				if (data != null) {
					mesh.gpu = device.createBuffer(() -> "MapDraw chunk " + mesh.originX + "," + mesh.originZ, VERTEX_BUFFER_USAGE, data.vertexBuffer());
					mesh.indexCount = data.drawState().indexCount();
				}
			}
		}
	}

	// ------------------------------------------------------------------ rendu compatibilité

	/** Rendu de secours via le pipeline vanilla (LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN). */
	public void renderCompat(LevelRenderContext context) {
		ClientConfig cfg = ClientConfig.get();

		if (!cfg.showInWorld || cfg.renderMode != ClientConfig.RenderMode.COMPAT || meshes.isEmpty()) {
			return;
		}

		Vec3 cam = context.levelState().cameraRenderState.pos;
		double maxDist = cfg.maxDistance + 12.0;
		double maxDistSq = maxDist * maxDist;
		PoseStack poseStack = context.poseStack();

		for (ChunkMesh mesh : meshes.values()) {
			double dx = mesh.originX + 8 - cam.x;
			double dz = mesh.originZ + 8 - cam.z;

			if (dx * dx + dz * dz > maxDistSq || mesh.vertexCount == 0) {
				continue;
			}

			poseStack.pushPose();
			poseStack.translate(mesh.originX - cam.x, -cam.y, mesh.originZ - cam.z);
			final float[] p = mesh.pos;
			final int[] c = mesh.col;
			final int count = mesh.vertexCount;
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugFilledBox(), (pose, buffer) -> {
				for (int v = 0; v < count; v++) {
					buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], p[v * 3 + 2]).setColor(c[v]);
				}
			});
			poseStack.popPose();
		}
	}

	/** Nombre de blocs fantômes actuellement construits (pour l'écran de config). */
	public int ghostCount() {
		int n = 0;

		for (ChunkMesh mesh : meshes.values()) {
			n += mesh.cells.length;
		}

		return n;
	}

	public void markAllGpuDirty() {
		for (ChunkMesh mesh : meshes.values()) {
			mesh.closeGpu();
		}
	}

	// ------------------------------------------------------------------ petites listes primitives

	private static final class FloatList {
		private float[] data;
		private int size;

		FloatList(int capacity) {
			data = new float[Math.max(16, capacity)];
		}

		void add(float a, float b, float c) {
			if (size + 3 > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}

			data[size++] = a;
			data[size++] = b;
			data[size++] = c;
		}

		float[] toArray() {
			return java.util.Arrays.copyOf(data, size);
		}
	}

	private static final class IntList {
		private int[] data;
		private int size;

		IntList(int capacity) {
			data = new int[Math.max(16, capacity)];
		}

		void add(int v) {
			if (size == data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}

			data[size++] = v;
		}

		int[] toArray() {
			return java.util.Arrays.copyOf(data, size);
		}
	}

}
