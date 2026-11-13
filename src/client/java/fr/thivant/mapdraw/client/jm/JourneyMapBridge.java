package fr.thivant.mapdraw.client.jm;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.lwjgl.sdl.SDLMouse;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.event.FullscreenDisplayEvent;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.util.PolygonHelper;
import journeymap.api.v2.common.event.ClientEventRegistry;
import journeymap.api.v2.common.event.FullscreenEventRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.client.ClientConfig;
import fr.thivant.mapdraw.client.DrawTools;
import fr.thivant.mapdraw.client.LayerManager;
import fr.thivant.mapdraw.client.MapDrawClient;
import fr.thivant.mapdraw.client.gui.Keys;
import fr.thivant.mapdraw.client.gui.MapDrawScreen;
import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;

/**
 * Pont entre MapDraw et JourneyMap :
 * <ul>
 *   <li>affiche les tracés en overlays (carte plein écran + minimap), un groupe de polygones par chunk ;</li>
 *   <li>permet de dessiner directement sur la carte plein écran (bouton « Dessin ») ;</li>
 *   <li>bouton « Outils MapDraw » pour la couleur, l'épaisseur et les calques.</li>
 * </ul>
 */
final class JourneyMapBridge implements LayerManager.Listener {
	private static final int OVERLAY_BUDGET_PER_TICK = 32;
	private static final int OVERLAY_Y = 64;

	private final IClientAPI api;
	private final LayerManager layers = MapDrawClient.layers();
	private final DrawTools tools = MapDrawClient.tools();
	private final Map<Long, List<PolygonOverlay>> overlays = new HashMap<>();
	private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
	private final List<PolygonOverlay> preview = new ArrayList<>();

	private boolean drawMode;
	private boolean mapping;
	private String dimension = "";
	private float lastOpacity = -1f;
	private long lastPreviewEnd = Long.MIN_VALUE;
	/** Dernier bouton souris (convention SDL de Minecraft) reçu par l'écran JourneyMap. */
	private int lastButton = -1;

	JourneyMapBridge(IClientAPI api) {
		this.api = api;
	}

	void register() {
		layers.addListener(this);
		MapDrawClient.addTickHook(this::tick);

		ClientEventRegistry.MAPPING_EVENT.subscribe(MapDraw.MOD_ID, e -> safe("onMapping", () -> onMapping(e)));
		FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(MapDraw.MOD_ID, e -> safe("onClick", () -> onClick(e)));
		FullscreenEventRegistry.FULLSCREEN_MAP_DRAG_EVENT.subscribe(MapDraw.MOD_ID, e -> safe("onDrag", () -> onDrag(e)));
		FullscreenEventRegistry.FULLSCREEN_MAP_MOVE_EVENT.subscribe(MapDraw.MOD_ID, e -> safe("onMove", () -> onMove(e)));
		FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(MapDraw.MOD_ID, e -> safe("onAddonButtons", () -> onAddonButtons(e)));
		FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(MapDraw.MOD_ID, e -> safe("onRender", () -> onRender(e)));

		// Les événements JourneyMap ne précisent pas la convention des boutons souris :
		// on lit le vrai bouton (convention Minecraft/SDL) via l'API écran de Fabric.
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> safe("hookScreen", () -> hookScreen(screen)));
	}

	private void hookScreen(Screen screen) {
		MapDraw.LOGGER.info("MapDraw : écran ouvert {}", screen.getClass().getName());
		{
			if (!screen.getClass().getName().startsWith("journeymap.")) {
				return;
			}

			ScreenMouseEvents.beforeMouseClick(screen).register((s, event) -> lastButton = event.input());
			ScreenMouseEvents.afterMouseRelease(screen).register((s, event, consumed) -> {
				tools.endStroke();
				return consumed;
			});
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (!drawMode) {
					return true;
				}

				if (Keys.isUndo(event)) {
					layers.undo();
					return false;
				}

				if (Keys.isRedo(event)) {
					layers.redo();
					return false;
				}

				if (Keys.isBackspace(event) && tools.removeLastPoint()) {
					lastPreviewEnd = Long.MIN_VALUE;
					return false;
				}

				if (Keys.isEnter(event) && tools.finishPolygon(MapDrawClient.currentDimension())) {
					clearPreview();
					return false;
				}

				return true;
			});
			ScreenEvents.remove(screen).register(s -> {
				tools.endStroke();
				clearPreview();
			});
		}
	}

	/** Une erreur dans MapDraw ne doit jamais casser JourneyMap : on la journalise et on continue. */
	private static void safe(String where, Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			MapDraw.LOGGER.error("MapDraw/JourneyMap : erreur dans {}", where, t);
		}
	}

	// ------------------------------------------------------------------ dessin sur la carte plein écran

	private static String dimId(ResourceKey<Level> level) {
		return level.identifier().toString();
	}

	private boolean isLeft(int jmButton) {
		return lastButton >= 0 ? lastButton == SDLMouse.SDL_BUTTON_LEFT : jmButton == SDLMouse.SDL_BUTTON_LEFT;
	}

	private boolean isRight(int jmButton) {
		return lastButton >= 0 ? lastButton == SDLMouse.SDL_BUTTON_RIGHT : jmButton == SDLMouse.SDL_BUTTON_RIGHT;
	}

	private void onClick(FullscreenMapEvent.ClickEvent event) {
		if (!drawMode || event.getStage() != FullscreenMapEvent.Stage.PRE) {
			return;
		}

		BlockPos pos = event.getLocation();

		if (isLeft(event.getButton())) {
			tools.press(dimId(event.getLevel()), pos.getX(), pos.getZ());
			lastPreviewEnd = Long.MIN_VALUE;

			if (!tools.hasPending()) {
				clearPreview();
			}

			event.cancel();
		} else if (isRight(event.getButton()) && (tools.finishPolygon(dimId(event.getLevel())) || tools.cancel())) {
			clearPreview();
			event.cancel();
		}
	}

	private void onDrag(FullscreenMapEvent.MouseDraggedEvent event) {
		if (!drawMode || event.getStage() != FullscreenMapEvent.Stage.PRE) {
			return;
		}

		BlockPos pos = event.getLocation();

		if (tools.isStroking()) {
			tools.drag(pos.getX(), pos.getZ());
		} else if (isLeft(event.getButton()) && (tools.tool() == DrawTools.Tool.FREEHAND || tools.tool() == DrawTools.Tool.ERASER)) {
			// Au cas où JourneyMap n'émettrait le clic qu'au relâchement : le trait démarre au premier glisser.
			tools.press(dimId(event.getLevel()), pos.getX(), pos.getZ());
		}

		// En mode dessin, le glisser gauche ne déplace pas la carte.
		if (isLeft(event.getButton())) {
			event.cancel();
		}
	}

	private void onMove(FullscreenMapEvent.MouseMoveEvent event) {
		if (!drawMode) {
			return;
		}

		BlockPos pos = event.getLocation();
		long end = CellKey.pack(pos.getX(), pos.getZ());

		if (end == lastPreviewEnd) {
			return;
		}

		lastPreviewEnd = end;
		Map<Long, Integer> cells = tools.preview(pos.getX(), pos.getZ());
		clearPreview();

		if (cells == null || cells.isEmpty()) {
			return;
		}

		preview.addAll(buildOverlays(dimId(event.getLevel()), cells, 0.45f, "MapDraw (aperçu)"));
		showAll(preview);
	}

	private void clearPreview() {
		for (PolygonOverlay overlay : preview) {
			api.remove(overlay);
		}

		preview.clear();
		lastPreviewEnd = Long.MIN_VALUE;
	}

	private void onAddonButtons(FullscreenDisplayEvent.AddonButtonDisplayEvent event) {
		MapDraw.LOGGER.info("MapDraw : ajout des boutons sur la carte JourneyMap");
		Screen jmScreen = event.getFullscreen().getScreen();
		event.getThemeButtonDisplay().addThemeToggleButton("Dessin : ON", "Dessin : OFF", MapDraw.id("textures/gui/jm_draw.png"), drawMode, (IThemeButton b) -> {
			drawMode = !drawMode;
			b.setToggled(drawMode);
			tools.endStroke();
			tools.cancel();
			clearPreview();
		});
		event.getThemeButtonDisplay().addThemeButton("Outils MapDraw", MapDraw.id("textures/gui/jm_tools.png"),
				(IThemeButton b) -> Minecraft.getInstance().gui.setScreen(new MapDrawScreen(jmScreen)));
	}

	private void onRender(FullscreenRenderEvent event) {
		if (!drawMode) {
			return;
		}

		GuiGraphicsExtractor g = event.getGraphics();
		Screen screen = event.getFullscreen().getScreen();
		var font = Minecraft.getInstance().font;
		var active = layers.active();
		String text = "MapDraw – " + tools.tool().label
				+ " · épaisseur " + tools.width()
				+ " · calque : " + (active != null ? active.meta().name() : "(nouveau)")
				+ (tools.tool().isShape() ? (tools.filled() ? " · surface" : " · contour") : "")
				+ (tools.hint() != null ? " · " + tools.hint() : "")
				+ " · Ctrl+Z / Ctrl+Y";
		int w = font.width(text) + 22;
		int x = (screen.width - w) / 2;
		int y = screen.height - 34;
		g.fill(x, y, x + w, y + 14, 0xC0000000);
		g.fill(x + 3, y + 3, x + 11, y + 11, 0xFF000000 | tools.color());
		g.text(font, text, x + 15, y + 3, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ overlays

	@Override
	public void chunkChanged(String dim, long chunkKey) {
		if (dim.equals(dimension)) {
			dirty.add(chunkKey);
		}
	}

	private void onMapping(MappingEvent event) {
		if (event.getStage() == MappingEvent.Stage.MAPPING_STARTED) {
			mapping = true;
			rebuildAll();
		} else {
			mapping = false;
			api.removeAll(MapDraw.MOD_ID);
			overlays.clear();
			preview.clear();
			dirty.clear();
		}
	}

	private void rebuildAll() {
		for (List<PolygonOverlay> list : overlays.values()) {
			for (PolygonOverlay overlay : list) {
				api.remove(overlay);
			}
		}

		overlays.clear();
		dirty.clear();
		dirty.addAll(layers.visibleChunks(dimension));
	}

	private void tick() {
		String dim = MapDrawClient.currentDimension();
		float opacity = ClientConfig.get().journeyMapOpacity;
		float signature = opacity * 1000f + ClientConfig.get().surfaceOpacity;

		if (!dim.equals(dimension) || signature != lastOpacity) {
			dimension = dim;
			lastOpacity = signature;
			rebuildAll();
		}

		if (!mapping || dimension.isEmpty() || !api.playerAccepts(MapDraw.MOD_ID, DisplayType.Polygon)) {
			return;
		}

		int budget = OVERLAY_BUDGET_PER_TICK;
		Iterator<Long> it = dirty.iterator();

		while (budget-- > 0 && it.hasNext()) {
			long chunk = it.next();
			it.remove();
			List<PolygonOverlay> old = overlays.remove(chunk);

			if (old != null) {
				for (PolygonOverlay overlay : old) {
					api.remove(overlay);
				}
			}

			Map<Long, Integer> cells = layers.mergedChunk(dimension, chunk);

			if (cells.isEmpty()) {
				continue;
			}

			List<PolygonOverlay> created = buildOverlays(dimension, cells, opacity, "MapDraw");
			showAll(created);
			overlays.put(chunk, created);
		}
	}

	private void showAll(List<PolygonOverlay> list) {
		for (PolygonOverlay overlay : list) {
			try {
				api.show(overlay);
			} catch (Throwable t) {
				MapDraw.LOGGER.debug("Overlay JourneyMap refusé", t);
			}
		}
	}

	/** Un polygone (avec trous) par zone de même couleur, aligné sur la grille des blocs. */
	private List<PolygonOverlay> buildOverlays(String dim, Map<Long, Integer> cells, float opacity, String group) {
		Map<Integer, Area> byColor = new HashMap<>();
		Map<Integer, Map<Integer, List<Integer>>> rows = new HashMap<>();

		for (Map.Entry<Long, Integer> e : cells.entrySet()) {
			rows.computeIfAbsent(e.getValue(), k -> new HashMap<>())
					.computeIfAbsent(CellKey.z(e.getKey()), k -> new ArrayList<>())
					.add(CellKey.x(e.getKey()));
		}

		// Regroupe les cases d'une même ligne en segments pour limiter le nombre de rectangles.
		for (Map.Entry<Integer, Map<Integer, List<Integer>>> byRgb : rows.entrySet()) {
			Area area = new Area();

			for (Map.Entry<Integer, List<Integer>> row : byRgb.getValue().entrySet()) {
				List<Integer> xs = row.getValue();
				xs.sort(Integer::compare);
				int start = xs.get(0);
				int prev = start;

				for (int i = 1; i <= xs.size(); i++) {
					int x = i < xs.size() ? xs.get(i) : Integer.MIN_VALUE;

					if (x != prev + 1) {
						area.add(new Area(new Rectangle(start, row.getKey(), prev - start + 1, 1)));
						start = x;
					}

					prev = x;
				}
			}

			byColor.put(byRgb.getKey(), area);
		}

		List<PolygonOverlay> out = new ArrayList<>();
		ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim));

		float surfaceOpacity = Math.min(opacity, ClientConfig.get().surfaceOpacity);

		for (Map.Entry<Integer, Area> e : byColor.entrySet()) {
			int value = e.getKey();
			int rgb = CellValue.rgb(value);
			float fill = CellValue.isSurface(value) ? surfaceOpacity : opacity;
			ShapeProperties props = new ShapeProperties()
					.setFillColor(rgb).setFillOpacity(fill)
					.setStrokeColor(rgb).setStrokeOpacity(0f).setStrokeWidth(1f);

			for (MapPolygonWithHoles polygon : PolygonHelper.createPolygonFromArea(e.getValue(), OVERLAY_Y)) {
				PolygonOverlay overlay = new PolygonOverlay(MapDraw.MOD_ID, dimKey, props, polygon);
				overlay.setOverlayGroupName(group);
				out.add(overlay);
			}
		}

		return out;
	}
}
