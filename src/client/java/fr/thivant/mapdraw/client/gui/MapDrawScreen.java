package fr.thivant.mapdraw.client.gui;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLMouse;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import fr.thivant.mapdraw.client.ClientConfig;
import fr.thivant.mapdraw.client.DrawTools;
import fr.thivant.mapdraw.client.LayerManager;
import fr.thivant.mapdraw.client.MapDrawClient;
import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;
import fr.thivant.mapdraw.core.LongIntMap;
import fr.thivant.mapdraw.core.LayerData;

/**
 * Écran MapDraw autonome (touche N) : panneau d'outils + carte de dessin intégrée.
 * Avec JourneyMap, le même panneau s'affiche directement sur la carte plein écran de JourneyMap.
 */
public final class MapDrawScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final double MIN_SCALE = 0.25;
	private static final double MAX_SCALE = 16.0;

	private final @Nullable Screen parent;
	private final LayerManager layers = MapDrawClient.layers();
	private final DrawTools tools = MapDrawClient.tools();
	private final TerrainColors terrain = new TerrainColors();
	private final ToolPanel panel = new ToolPanel(() -> this, this::onClose);

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

		panel.setBounds(0, 0, height);
	}

	private String dimension() {
		return MapDrawClient.currentDimension();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Pas de flou : la carte occupe tout l'écran.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		drawMap(g, mouseX, mouseY);
		panel.render(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private int mapLeft() {
		return ToolPanel.WIDTH;
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
		float surfaceOpacity = ClientConfig.get().surfaceOpacity;

		int[] grid = new int[256];

		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cz = minCZ; cz <= maxCZ; cz++) {
				long ck = CellKey.chunkKey(cx, cz);

				if (!layers.hasCells(dim, ck)) {
					continue;
				}

				layers.chunkGrid(dim, ck, grid);

				// Une seule bande par suite de cases identiques sur une ligne : beaucoup moins de rectangles.
				for (int lz = 0; lz < 16; lz++) {
					int sy = screenY((cz << 4) + lz);
					int ey = screenY((cz << 4) + lz + 1);
					int lx = 0;

					while (lx < 16) {
						int v = grid[(lz << 4) | lx];

						if (v == LongIntMap.NONE) {
							lx++;
							continue;
						}

						int start = lx;

						while (lx < 16 && grid[(lz << 4) | lx] == v) {
							lx++;
						}

						int sx = screenX((cx << 4) + start);
						int ex = screenX((cx << 4) + lx);
						g.fill(sx, sy, Math.max(ex, sx + 1), Math.max(ey, sy + 1),
								alpha(CellValue.opacity(v, CellValue.isSurface(v) ? surfaceOpacity : 0.82f)) | CellValue.rgb(v));
					}
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
					g.fill(sx, sy, sx + cell, sy + cell, (CellValue.isSurface(e.getValue()) ? 0x50000000 : 0xC0000000) | CellValue.rgb(e.getValue()));
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

	private static int alpha(float opacity) {
		return Math.round(Math.max(0f, Math.min(1f, opacity)) * 255f) << 24;
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
		double mx = click.x();
		double my = click.y();
		int button = click.input();

		if (panel.mouseClicked(mx, my, button)) {
			return true;
		}

		if (!inMap(mx, my)) {
			return super.mouseClicked(click, doubleClick);
		}

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
		if (panel.mouseDragged(click.x(), click.y())) {
			return true;
		}

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
		if (panel.mouseReleased()) {
			return true;
		}

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
		if (panel.mouseScrolled(mouseX, mouseY, vertical)) {
			return true;
		}

		if (inMap(mouseX, mouseY) && vertical != 0) {
			double bx = centerX + (mouseX - (mapLeft() + width) / 2.0) / scale;
			double bz = centerZ + (mouseY - height / 2.0) / scale;
			double newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * (vertical > 0 ? 1.25 : 0.8)));
			centerX = bx - (mouseX - (mapLeft() + width) / 2.0) / newScale;
			centerZ = bz - (mouseY - height / 2.0) / newScale;
			scale = newScale;
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (panel.keyPressed(event)) {
			return true;
		}

		if (Keys.isUndo(event)) {
			layers.undo();
			return true;
		}

		if (Keys.isRedo(event)) {
			layers.redo();
			return true;
		}

		if (tools.tool() == DrawTools.Tool.SELECT && tools.selected() != null) {
			int dx = Keys.arrowDx(event);
			int dz = Keys.arrowDz(event);

			if ((dx != 0 || dz != 0) && tools.nudge(dx, dz)) {
				return true;
			}

			if (Keys.isDelete(event) && tools.deleteSelected()) {
				return true;
			}
		}

		if (Keys.isBackspace(event) && tools.removeLastPoint()) {
			return true;
		}

		if (Keys.isEnter(event) && tools.finishPolygon(dimension())) {
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return panel.charTyped(event) || super.charTyped(event);
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
