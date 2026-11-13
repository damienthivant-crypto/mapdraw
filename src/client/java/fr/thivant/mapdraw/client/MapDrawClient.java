package fr.thivant.mapdraw.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.client.gui.MapDrawScreen;
import fr.thivant.mapdraw.client.render.GhostRenderer;

/**
 * Point d'entrée client.
 */
public final class MapDrawClient implements ClientModInitializer {
	private static final int AUTOSAVE_TICKS = 100;
	private static final List<Runnable> TICK_HOOKS = new ArrayList<>();

	private static LayerManager layers;
	private static DrawTools tools;
	private static GhostRenderer renderer;
	private static KeyMapping openKey;
	private static KeyMapping toggleKey;
	private static int saveCounter;
	private static String currentDimension = "";

	public static LayerManager layers() {
		return layers;
	}

	public static DrawTools tools() {
		return tools;
	}

	public static GhostRenderer renderer() {
		return renderer;
	}

	public static String currentDimension() {
		return currentDimension;
	}

	public static boolean journeyMapLoaded() {
		return FabricLoader.getInstance().isModLoaded("journeymap");
	}

	/** Permet à l'intégration JourneyMap de s'exécuter à chaque tick. */
	public static void addTickHook(Runnable hook) {
		TICK_HOOKS.add(hook);
	}

	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		layers = new LayerManager();
		tools = new DrawTools(layers);
		renderer = new GhostRenderer(layers);
		layers.addListener(renderer);
		ClientNet.init(layers);

		KeyMapping.Category category = KeyMapping.Category.register(MapDraw.id("main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mapdraw.open", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_N, category));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mapdraw.toggle_world", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_K, category));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			updateSelf(client);
			ClientStorage.open(client, layers);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			tools.endStroke();
			ClientStorage.close(layers);
			layers.reset();
			renderer.clear();
			currentDimension = "";
		});

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> renderer.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> renderer.onChunkUnloaded(chunk.getPos().x(), chunk.getPos().z()));

		ClientTickEvents.END_CLIENT_TICK.register(MapDrawClient::tick);

		LevelRenderEvents.AFTER_RENDER_PASS.register(context -> renderer.renderGpu(context));
		LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> renderer.renderCompat(context));

		MapDraw.LOGGER.info("MapDraw client prêt (JourneyMap {})", journeyMapLoaded() ? "détecté" : "absent : écran de carte intégré");
	}

	private static void updateSelf(Minecraft mc) {
		if (mc.player != null) {
			layers.setSelf(mc.player.getUUID(), mc.player.getName().getString());
		}
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			return;
		}

		if (mc.player != null && !mc.player.getUUID().equals(layers.selfId())) {
			updateSelf(mc);
		}

		String dim = mc.level.dimension().identifier().toString();

		if (!dim.equals(currentDimension)) {
			currentDimension = dim;
			tools.setTool(tools.tool());
			tools.deselect();
			renderer.setDimension(dim);
		}

		while (openKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new MapDrawScreen(null));
			}
		}

		while (toggleKey.consumeClick()) {
			ClientConfig cfg = ClientConfig.get();
			cfg.showInWorld = !cfg.showInWorld;
			cfg.save();
			ClientNet.chat("MapDraw : affichage dans le monde " + (cfg.showInWorld ? "activé" : "désactivé"));
		}

		layers.flushOutgoing();
		String message = layers.pollMessage();

		if (message != null) {
			ClientNet.chat("MapDraw : " + message);
		}

		if (++saveCounter >= AUTOSAVE_TICKS) {
			saveCounter = 0;

			if (layers.consumePrivateDirty()) {
				ClientStorage.savePrivate(layers);
			}

			if (layers.consumeViewDirty()) {
				ClientStorage.saveView(layers);
			}
		}

		renderer.tick(mc.level);

		for (Runnable hook : TICK_HOOKS) {
			try {
				hook.run();
			} catch (Throwable t) {
				MapDraw.LOGGER.error("Erreur dans l'intégration MapDraw", t);
			}
		}
	}
}
