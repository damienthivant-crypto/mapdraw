package fr.thivant.mapdraw.client.gui;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.thivant.mapdraw.core.CellKey;

/**
 * Couleurs de fond de la carte intégrée (utilisée quand JourneyMap est absent), façon carte vanilla.
 * Un tableau de 16x16 couleurs par chunk, recalculé périodiquement.
 */
final class TerrainColors {
	private static final long MAX_AGE_MS = 4000;
	private static final int UNLOADED = 0xFF101010;

	private static final class Entry {
		final int[] colors = new int[256];
		long time;
	}

	private final Map<Long, Entry> cache = new HashMap<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private int budget;

	void beginFrame() {
		budget = 48;

		if (cache.size() > 4096) {
			cache.clear();
		}
	}

	/** Couleur ARGB de la colonne (x, z). */
	int color(ClientLevel level, int x, int z) {
		long key = CellKey.chunkKey(x >> 4, z >> 4);
		Entry e = cache.get(key);
		long now = System.currentTimeMillis();

		if (e == null || (now - e.time > MAX_AGE_MS && budget > 0)) {
			if (budget <= 0 && e == null) {
				return UNLOADED;
			}

			budget--;
			e = compute(level, x >> 4, z >> 4, e);
			e.time = now;
			cache.put(key, e);
		}

		return e.colors[((z & 15) << 4) | (x & 15)];
	}

	private Entry compute(ClientLevel level, int cx, int cz, Entry reuse) {
		Entry e = reuse != null ? reuse : new Entry();

		if (!level.hasChunk(cx, cz)) {
			java.util.Arrays.fill(e.colors, UNLOADED);
			return e;
		}

		int bx = cx << 4;
		int bz = cz << 4;

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int x = bx + lx;
				int z = bz + lz;
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				pos.set(x, y, z);
				BlockState state = level.getBlockState(pos);
				int rgb = state.getMapColor(level, pos).col;

				if (rgb == 0) {
					rgb = 0x3A3A3A;
				}

				// Relief : on éclaircit ou assombrit selon la hauteur du voisin nord.
				int yn = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1;
				float shade = y > yn ? 1.08f : (y < yn ? 0.80f : 0.94f);
				e.colors[(lz << 4) | lx] = 0xFF000000 | scale(rgb, shade);
			}
		}

		return e;
	}

	private static int scale(int rgb, float f) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((rgb & 0xFF) * f));
		return (r << 16) | (g << 8) | b;
	}
}
