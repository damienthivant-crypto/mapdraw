package fr.thivant.mapdraw.client.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLMouse;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import fr.thivant.mapdraw.client.ClientConfig;
import fr.thivant.mapdraw.client.ClientNet;
import fr.thivant.mapdraw.client.ClientStorage;
import fr.thivant.mapdraw.client.DrawTools;
import fr.thivant.mapdraw.client.LayerManager;
import fr.thivant.mapdraw.client.MapDrawClient;
import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;
import fr.thivant.mapdraw.core.LayerData;

/**
 * Écran MapDraw : panneau d'outils et de calques + carte de dessin intégrée.
 * <p>
 * Avec JourneyMap, on peut aussi dessiner directement sur la carte plein écran de JourneyMap ;
 * cet écran sert alors surtout à gérer les calques, la couleur et l'épaisseur.
 */
public final class MapDrawScreen extends Screen {
	private static final int PANEL_W = 172;
	private static final int ROW_H = 12;
	private static final int SWATCH = 13;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;
	private static final double MIN_SCALE = 0.25;
	private static final double MAX_SCALE = 16.0;

	private final @Nullable Screen parent;
	private final LayerManager layers = MapDrawClient.layers();
	private final DrawTools tools = MapDrawClient.tools();
	private final TerrainColors terrain = new TerrainColors();

	private final java.util.EnumMap<DrawTools.Tool, Button> toolButtons = new java.util.EnumMap<>(DrawTools.Tool.class);
	private Button fillBtn;
	private Button layerWorldBtn;
	private Button undoBtn;
	private Button redoBtn;
	private Button shareBtn;
	private Button worldBtn;
	private EditBox hexBox;
	private EditBox nameBox;

	private int paletteY;
	private int widthY;
	private int listTop;
	private int listRows;
	private int listScroll;

	private double centerX;
	private double centerZ;
	private double scale = 2.0;
	private boolean panning;
	private boolean drawing;
	private int hoverX;
	private int hoverZ;
	private boolean hoverValid;

	public MapDrawScreen(@Nullable Screen parent) {
		super(Component.literal("MapDraw"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (minecraft.player != null && centerX == 0 && centerZ == 0) {
			centerX = minecraft.player.getX();
			centerZ = minecraft.player.getZ();
		}

		int x = 6;
		int y = 18;
		int w3 = (PANEL_W - 12 - 4) / 3;

		DrawTools.Tool[] all = DrawTools.Tool.values();

		for (int i = 0; i < all.length; i++) {
			DrawTools.Tool t = all[i];
			int bx = x + (i % 3) * (w3 + 2);
			int by = y + (i / 3) * 20;
			toolButtons.put(t, addRenderableWidget(Button.builder(Component.literal(t.shortLabel), b -> tools.setTool(t)).pos(bx, by).size(w3, 18).build()));
		}

		y += 40;
		fillBtn = addRenderableWidget(Button.builder(Component.literal("Formes"), b -> tools.setFilled(!tools.filled())).pos(x, y).size(PANEL_W - 12, 18).build());
		y += 24;

		paletteY = y;
		y += 2 * (SWATCH + 2) + 2;

		hexBox = addRenderableWidget(new EditBox(font, x + 26, y, 70, 16, Component.literal("Couleur hex")));
		hexBox.setMaxLength(7);
		hexBox.setValue(String.format("#%06X", tools.color()));
		hexBox.setResponder(this::onHexChanged);
		y += 20;

		widthY = y;
		addRenderableWidget(Button.builder(Component.literal("-"), b -> tools.setWidth(tools.width() - 1)).pos(x, y).size(18, 16).build());
		addRenderableWidget(Button.builder(Component.literal("+"), b -> tools.setWidth(tools.width() + 1)).pos(PANEL_W - 6 - 18, y).size(18, 16).build());
		y += 20;

		int w2 = (PANEL_W - 12 - 2) / 2;
		undoBtn = addRenderableWidget(Button.builder(Component.literal("Annuler"), b -> layers.undo()).pos(x, y).size(w2, 18).build());
		redoBtn = addRenderableWidget(Button.builder(Component.literal("Rétablir"), b -> layers.redo()).pos(x + w2 + 2, y).size(w2, 18).build());
		y += 22;

		// Liste des calques : place restante moins les boutons du bas (6 rangées).
		listTop = y + 11;
		int bottomBlock = 20 + 6 * 20 + 24;
		listRows = Math.max(3, (height - listTop - bottomBlock) / ROW_H);
		y = listTop + listRows * ROW_H + 3;

		nameBox = addRenderableWidget(new EditBox(font, x, y, PANEL_W - 12, 16, Component.literal("Nom du calque")));
		nameBox.setMaxLength(48);
		y += 20;

		addRenderableWidget(Button.builder(Component.literal("Nouveau"), b -> newLayer()).pos(x, y).size(w2, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Renommer"), b -> renameLayer()).pos(x + w2 + 2, y).size(w2, 18).build());
		y += 20;
		shareBtn = addRenderableWidget(Button.builder(Component.literal("Partager"), b -> toggleShare()).pos(x, y).size(w2, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Supprimer"), b -> deleteLayer()).pos(x + w2 + 2, y).size(w2, 18).build());
		y += 20;
		layerWorldBtn = addRenderableWidget(Button.builder(Component.literal("Monde"), b -> {
			LayerData active = layers.active();

			if (active != null) {
				layers.toggleWorld(active.meta().id());
			}
		}).pos(x, y).size(PANEL_W - 12, 18).build());
		y += 20;
		addRenderableWidget(Button.builder(Component.literal("Exporter"), b -> exportLayer()).pos(x, y).size(w2, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Importer"), b -> minecraft.gui.setScreen(new ImportScreen(this))).pos(x + w2 + 2, y).size(w2, 18).build());
		y += 20;
		addRenderableWidget(Button.builder(Component.literal("Tout effacer"), b -> clearLayer()).pos(x, y).size(w2, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Réglages"), b -> minecraft.gui.setScreen(new ConfigScreen(this))).pos(x + w2 + 2, y).size(w2, 18).build());
		y += 20;
		worldBtn = addRenderableWidget(Button.builder(Component.literal("Monde"), b -> toggleWorld()).pos(x, y).size(w2, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> onClose()).pos(x + w2 + 2, y).size(w2, 18).build());

		refreshLabels();
	}

	// ------------------------------------------------------------------ actions

	private String dimension() {
		return MapDrawClient.currentDimension();
	}

	private void onHexChanged(String value) {
		String v = value.startsWith("#") ? value.substring(1) : value;

		if (v.length() == 6) {
			try {
				tools.setColor(Integer.parseInt(v, 16));
			} catch (NumberFormatException ignored) {
				// saisie en cours
			}
		}
	}

	private void newLayer() {
		String name = nameBox.getValue().isBlank() ? "Calque " + (layers.inDimension(dimension()).size() + 1) : nameBox.getValue();
		layers.createLayer(name, dimension());
		nameBox.setValue("");
	}

	private void renameLayer() {
		LayerData active = layers.active();

		if (active != null && !nameBox.getValue().isBlank()) {
			layers.rename(active.meta().id(), nameBox.getValue());
			nameBox.setValue("");
		}
	}

	private void toggleShare() {
		LayerData active = layers.active();

		if (active != null) {
			layers.setShared(active.meta().id(), !active.meta().shared());
		}
	}

	private void deleteLayer() {
		LayerData active = layers.active();

		if (active == null) {
			return;
		}

		minecraft.gui.setScreen(new ConfirmScreen(this,
				"Supprimer le calque « " + active.meta().name() + " » ?",
				active.meta().shared() ? "Il sera supprimé pour tous les joueurs." : "Cette action est définitive.",
				() -> layers.delete(active.meta().id())));
	}

	private void clearLayer() {
		LayerData active = layers.active();

		if (active == null) {
			return;
		}

		minecraft.gui.setScreen(new ConfirmScreen(this,
				"Tout effacer sur « " + active.meta().name() + " » ?",
				active.size() + " bloc(s) seront effacés (Ctrl+Z pour annuler).",
				() -> layers.clearLayer(active.meta().id())));
	}

	private void exportLayer() {
		LayerData active = layers.active();

		if (active == null) {
			return;
		}

		try {
			Path file = ClientStorage.exportLayer(active);
			ClientNet.chat("MapDraw : calque exporté vers " + file.toAbsolutePath());
		} catch (IOException e) {
			ClientNet.chat("MapDraw : export impossible (" + e.getMessage() + ")");
		}
	}

	private void toggleWorld() {
		ClientConfig cfg = ClientConfig.get();
		cfg.showInWorld = !cfg.showInWorld;
		cfg.save();
	}

	private void refreshLabels() {
		DrawTools.Tool t = tools.tool();

		for (Map.Entry<DrawTools.Tool, Button> e : toolButtons.entrySet()) {
			e.getValue().setMessage(Component.literal(e.getKey() == t ? "» " + e.getKey().shortLabel : e.getKey().shortLabel));
		}

		fillBtn.setMessage(Component.literal("Formes : " + (tools.filled() ? "surface teintée" : "contour seul")));
		undoBtn.active = layers.canUndo();
		redoBtn.active = layers.canRedo();
		LayerData active = layers.active();
		shareBtn.setMessage(Component.literal(active != null && active.meta().shared() ? "Rendre privé" : "Partager"));
		shareBtn.active = active != null && layers.serverMode() && layers.isOwn(active);
		layerWorldBtn.active = active != null;
		layerWorldBtn.setMessage(Component.literal("Calque dans le monde : " + (active != null && layers.isWorldHidden(active.meta().id()) ? "caché" : "visible")));
		worldBtn.setMessage(Component.literal("Monde : " + (ClientConfig.get().showInWorld ? "ON" : "OFF")));
	}

	// ------------------------------------------------------------------ rendu

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Pas de flou : la carte occupe tout l'écran.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		refreshLabels();
		drawMap(g, mouseX, mouseY);

		// Panneau
		g.fill(0, 0, PANEL_W, height, 0xE0141414);
		g.fill(PANEL_W - 1, 0, PANEL_W, height, 0xFF3A3A3A);
		g.text(font, "MapDraw", 6, 5, TEXT);
		String mode = layers.serverMode() ? "serveur" : "local";
		g.text(font, mode, PANEL_W - 6 - font.width(mode), 5, layers.serverMode() ? 0xFF7CD67C : 0xFFE0C060);

		// Palette
		for (int i = 0; i < DrawTools.PALETTE.length; i++) {
			int sx = 6 + (i % 6) * (SWATCH + 2);
			int sy = paletteY + (i / 6) * (SWATCH + 2);
			int rgb = DrawTools.PALETTE[i];

			if (rgb == tools.color()) {
				g.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy + SWATCH + 1, 0xFFFFFFFF);
			}

			g.fill(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF000000 | rgb);
		}

		int hexY = paletteY + 2 * (SWATCH + 2) + 2;
		g.fill(6, hexY, 6 + 18, hexY + 16, 0xFF000000 | tools.color());

		String widthText = "Épaisseur : " + tools.width() + (tools.width() > 1 ? " blocs" : " bloc");
		g.text(font, widthText, (PANEL_W - font.width(widthText)) / 2, widthY + 4, TEXT);

		drawLayerList(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private void drawLayerList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, "Calques (" + dimensionShort() + ")", 6, listTop - 10, TEXT_DIM);
		g.fill(6, listTop, PANEL_W - 6, listTop + listRows * ROW_H, 0x80000000);
		List<LayerData> list = layers.inDimension(dimension());
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - listRows)));

		if (list.isEmpty()) {
			g.text(font, "Aucun calque : dessinez !", 10, listTop + 2, TEXT_DIM);
		}

		for (int i = 0; i < listRows && i + listScroll < list.size(); i++) {
			LayerData layer = list.get(i + listScroll);
			int ry = listTop + i * ROW_H;
			boolean selected = layer.meta().id().equals(layers.activeId());
			boolean hovered = mouseX >= 6 && mouseX < PANEL_W - 6 && mouseY >= ry && mouseY < ry + ROW_H;

			if (selected) {
				g.fill(6, ry, PANEL_W - 6, ry + ROW_H, 0x60FFFFFF);
			} else if (hovered) {
				g.fill(6, ry, PANEL_W - 6, ry + ROW_H, 0x30FFFFFF);
			}

			boolean hidden = layers.isHidden(layer.meta().id());
			g.text(font, hidden ? "○" : "●", 9, ry + 2, hidden ? TEXT_DIM : 0xFF7CD67C);
			String badge = layer.meta().shared() ? (layers.isOwn(layer) ? "P" : layer.meta().ownerName()) : "";
			int badgeW = badge.isEmpty() ? 0 : font.width(badge) + 4;
			String label = layer.meta().name() + (layers.isWorldHidden(layer.meta().id()) ? " (carte)" : "");
			String name = fit(label, PANEL_W - 6 - 20 - badgeW - 4);
			g.text(font, name, 20, ry + 2, layers.canEdit(layer) ? TEXT : TEXT_DIM);

			if (!badge.isEmpty()) {
				g.text(font, badge, PANEL_W - 8 - font.width(badge), ry + 2, layers.isOwn(layer) ? 0xFF7FB2FF : 0xFFFFB060);
			}
		}
	}

	private String dimensionShort() {
		String d = dimension();
		int i = d.indexOf(':');
		return i >= 0 ? d.substring(i + 1) : d;
	}

	private String fit(String s, int maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}

		String out = s;

		while (!out.isEmpty() && font.width(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}

		return out + "…";
	}

	private int mapLeft() {
		return PANEL_W;
	}

	private int blockX(double mouseX) {
		double mid = (mapLeft() + width) / 2.0;
		return (int) Math.floor(centerX + (mouseX - mid) / scale);
	}

	private int blockZ(double mouseY) {
		double mid = height / 2.0;
		return (int) Math.floor(centerZ + (mouseY - mid) / scale);
	}

	private int screenX(double bx) {
		double mid = (mapLeft() + width) / 2.0;
		return (int) Math.floor(mid + (bx - centerX) * scale);
	}

	private int screenY(double bz) {
		double mid = height / 2.0;
		return (int) Math.floor(mid + (bz - centerZ) * scale);
	}

	private void drawMap(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int left = mapLeft();
		g.fill(left, 0, width, height, 0xFF0A0A0A);
		ClientLevel level = minecraft.level;

		if (level == null) {
			return;
		}

		g.enableScissor(left, 0, width, height);
		terrain.beginFrame();
		int minBX = blockX(left) - 1;
		int maxBX = blockX(width) + 1;
		int minBZ = blockZ(0) - 1;
		int maxBZ = blockZ(height) + 1;

		// Fond : pas d'échantillonnage adapté au zoom pour garder un nombre de rectangles raisonnable.
		int step = Math.max(1, (int) Math.ceil(3.0 / scale));
		int startX = Math.floorDiv(minBX, step) * step;
		int startZ = Math.floorDiv(minBZ, step) * step;

		for (int bz = startZ; bz <= maxBZ; bz += step) {
			int y0 = screenY(bz);
			int y1 = screenY(bz + step);
			int runStart = startX;
			int runColor = terrain.color(level, startX, bz);

			for (int bx = startX + step; bx <= maxBX + step; bx += step) {
				int c = bx <= maxBX ? terrain.color(level, bx, bz) : 0;

				if (c != runColor || bx > maxBX) {
					g.fill(screenX(runStart), y0, screenX(bx), y1, runColor);
					runStart = bx;
					runColor = c;
				}
			}
		}

		// Tracés (calques visibles de la dimension)
		int minCX = minBX >> 4;
		int maxCX = maxBX >> 4;
		int minCZ = minBZ >> 4;
		int maxCZ = maxBZ >> 4;
		String dim = dimension();
		int cell = Math.max(1, (int) Math.ceil(scale));
		int surfaceAlpha = Math.round(ClientConfig.get().surfaceOpacity * 255f) << 24;

		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cz = minCZ; cz <= maxCZ; cz++) {
				Map<Long, Integer> cells = layers.mergedChunk(dim, CellKey.chunkKey(cx, cz));

				for (Map.Entry<Long, Integer> e : cells.entrySet()) {
					int sx = screenX(CellKey.x(e.getKey()));
					int sy = screenY(CellKey.z(e.getKey()));
					int v = e.getValue();
					g.fill(sx, sy, sx + cell, sy + cell, (CellValue.isSurface(v) ? surfaceAlpha : 0xD0000000) | CellValue.rgb(v));
				}
			}
		}

		// Aperçu de la ligne
		hoverValid = mouseX >= left && mouseX < width && mouseY >= 0 && mouseY < height;
		hoverX = blockX(mouseX);
		hoverZ = blockZ(mouseY);

		if (hoverValid) {
			Map<Long, Integer> preview = tools.preview(hoverX, hoverZ);

			if (preview != null) {
				for (Map.Entry<Long, Integer> e : preview.entrySet()) {
					int sx = screenX(CellKey.x(e.getKey()));
					int sy = screenY(CellKey.z(e.getKey()));
					g.fill(sx, sy, sx + cell, sy + cell, (CellValue.isSurface(e.getValue()) ? 0x50000000 : 0x90000000) | tools.color());
				}
			}

			for (long p : tools.polygonPoints()) {
				int px = screenX(CellKey.x(p));
				int pz = screenY(CellKey.z(p));
				outline(g, px - 1, pz - 1, px + cell + 1, pz + cell + 1, 0xFFFFFFFF);
			}

			int lo = fr.thivant.mapdraw.core.Raster.brushLow(tools.width());
			int hi = fr.thivant.mapdraw.core.Raster.brushHigh(tools.width());
			int bx0 = screenX(hoverX + lo);
			int bz0 = screenY(hoverZ + lo);
			int bx1 = screenX(hoverX + hi + 1);
			int bz1 = screenY(hoverZ + hi + 1);
			outline(g, bx0, bz0, bx1, bz1, 0xFFFFFFFF);
		}

		// Joueur
		if (minecraft.player != null) {
			int px = screenX(minecraft.player.getX());
			int pz = screenY(minecraft.player.getZ());
			g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFF000000);
			g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFFFFFFFF);
		}

		g.disableScissor();

		// Infos en bas de la carte
		LayerData active = layers.active();
		String info = (hoverValid ? "X " + hoverX + "  Z " + hoverZ + "   " : "")
				+ "Outil : " + tools.tool().label
				+ "   Calque : " + (active != null ? active.meta().name() : "—")
				+ "   (clic droit : déplacer, molette : zoom)";
		g.fill(left, height - 12, width, height, 0xA0000000);
		g.text(font, info, left + 4, height - 10, TEXT);

		String hint = tools.hint();

		if (hint != null) {
			g.text(font, hint, left + 4, 4, 0xFFFFE070);
		}
	}

	private static void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y0 + 1, color);
		g.fill(x0, y1 - 1, x1, y1, color);
		g.fill(x0, y0, x0 + 1, y1, color);
		g.fill(x1 - 1, y0, x1, y1, color);
	}

	// ------------------------------------------------------------------ entrées

	private boolean inMap(double x, double y) {
		return x >= mapLeft() && x < width && y >= 0 && y < height;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}

		double mx = click.x();
		double my = click.y();
		int button = click.input();

		// Palette
		for (int i = 0; i < DrawTools.PALETTE.length; i++) {
			int sx = 6 + (i % 6) * (SWATCH + 2);
			int sy = paletteY + (i / 6) * (SWATCH + 2);

			if (mx >= sx && mx < sx + SWATCH && my >= sy && my < sy + SWATCH) {
				tools.setColor(DrawTools.PALETTE[i]);
				hexBox.setValue(String.format("#%06X", tools.color()));
				return true;
			}
		}

		// Liste des calques
		if (mx >= 6 && mx < PANEL_W - 6 && my >= listTop && my < listTop + listRows * ROW_H) {
			int index = (int) ((my - listTop) / ROW_H) + listScroll;
			List<LayerData> list = layers.inDimension(dimension());

			if (index >= 0 && index < list.size()) {
				LayerData layer = list.get(index);

				if (mx < 18) {
					layers.toggleHidden(layer.meta().id());
				} else {
					layers.setActive(layer.meta().id());
					nameBox.setValue(layer.meta().name());
				}
			}

			return true;
		}

		if (!inMap(mx, my)) {
			return false;
		}

		setFocused(null);

		if (button == SDLMouse.SDL_BUTTON_LEFT) {
			drawing = tools.press(dimension(), blockX(mx), blockZ(my));
			return true;
		}

		if (button == SDLMouse.SDL_BUTTON_RIGHT && (tools.finishPolygon(dimension()) || tools.cancel())) {
			return true;
		}

		if (button == SDLMouse.SDL_BUTTON_RIGHT || button == SDLMouse.SDL_BUTTON_MIDDLE) {
			panning = true;
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
		if (panning) {
			centerX -= dx / scale;
			centerZ -= dy / scale;
			return true;
		}

		if (drawing && click.input() == SDLMouse.SDL_BUTTON_LEFT) {
			tools.drag(blockX(click.x()), blockZ(click.y()));
			return true;
		}

		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		if (drawing || panning) {
			drawing = false;
			panning = false;
			tools.endStroke();
			return true;
		}

		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (mouseX >= 6 && mouseX < PANEL_W - 6 && mouseY >= listTop && mouseY < listTop + listRows * ROW_H) {
			listScroll -= (int) Math.signum(vertical);
			return true;
		}

		if (inMap(mouseX, mouseY) && vertical != 0) {
			double bx = centerX + (mouseX - (mapLeft() + width) / 2.0) / scale;
			double bz = centerZ + (mouseY - height / 2.0) / scale;
			double newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * (vertical > 0 ? 1.25 : 0.8)));
			// Zoom centré sur le curseur
			centerX = bx - (mouseX - (mapLeft() + width) / 2.0) / newScale;
			centerZ = bz - (mouseY - height / 2.0) / newScale;
			scale = newScale;
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = (hexBox != null && hexBox.isFocused()) || (nameBox != null && nameBox.isFocused());

		if (!typing) {
			if (Keys.isUndo(event)) {
				layers.undo();
				return true;
			}

			if (Keys.isRedo(event)) {
				layers.redo();
				return true;
			}

			if (Keys.isBackspace(event) && tools.removeLastPoint()) {
				return true;
			}

			if (Keys.isEnter(event) && tools.finishPolygon(dimension())) {
				return true;
			}
		}

		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		tools.endStroke();
		super.removed();
	}

	@Override
	public void onClose() {
		tools.endStroke();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
