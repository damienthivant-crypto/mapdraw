package fr.thivant.mapdraw.client.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.lwjgl.sdl.SDLMouse;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import fr.thivant.mapdraw.client.ClientConfig;
import fr.thivant.mapdraw.client.ClientNet;
import fr.thivant.mapdraw.client.ClientStorage;
import fr.thivant.mapdraw.client.DrawTools;
import fr.thivant.mapdraw.client.LayerManager;
import fr.thivant.mapdraw.client.MapDrawClient;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.Zone;

/**
 * Panneau d'outils MapDraw, dessiné à la main pour pouvoir s'afficher aussi bien dans l'écran MapDraw
 * que par-dessus la carte plein écran de JourneyMap. Il défile à la molette (ou avec la barre à droite).
 */
public final class ToolPanel {
	public static final int WIDTH = 176;
	private static final int PAD = 6;
	private static final int ROW = 20;
	private static final int BTN_H = 17;
	private static final int SWATCH = 14;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;
	private static final int SCROLLBAR_W = 5;

	/** Zone cliquable calculée à chaque image. */
	private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/** Petit champ de saisie maison. */
	private static final class TextField {
		final int maxLength;
		String value = "";
		boolean focused;

		TextField(int maxLength) {
			this.maxLength = maxLength;
		}
	}

	private final LayerManager layers = MapDrawClient.layers();
	private final DrawTools tools = MapDrawClient.tools();
	private final Supplier<Screen> host;
	private final Runnable onClose;
	private final List<Hit> hits = new ArrayList<>();
	private final TextField hexField = new TextField(7);
	private final TextField nameField = new TextField(48);

	private int x;
	private int y;
	private int height;
	private double scroll;
	private int contentHeight;
	private boolean draggingScrollbar;
	private int hexShownFor = -1;

	/**
	 * @param host    écran sur lequel le panneau est affiché (parent des boîtes de dialogue)
	 * @param onClose action du bouton « Fermer »
	 */
	public ToolPanel(Supplier<Screen> host, Runnable onClose) {
		this.host = host;
		this.onClose = onClose;
	}

	public void setBounds(int x, int y, int height) {
		this.x = x;
		this.y = y;
		this.height = height;
	}

	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + WIDTH && my >= y && my < y + height;
	}

	public boolean hasTextFocus() {
		return hexField.focused || nameField.focused;
	}

	// ------------------------------------------------------------------ rendu

	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Font font = Minecraft.getInstance().font;
		hits.clear();

		if (hexShownFor != tools.color() && !hexField.focused) {
			hexField.value = String.format("#%06X", tools.color());
			hexShownFor = tools.color();
		}

		g.fill(x, y, x + WIDTH, y + height, 0xE6141414);
		g.outline(x, y, WIDTH, height, 0xFF3A3A3A);
		g.enableScissor(x, y, x + WIDTH, y + height);

		int maxScroll = Math.max(0, contentHeight - height);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		int cy = y + PAD - (int) scroll;
		int left = x + PAD;
		int inner = WIDTH - 2 * PAD - SCROLLBAR_W;

		// En-tête
		g.text(font, "MapDraw", left, cy, TEXT);
		String mode = layers.serverMode() ? "serveur" : "local";
		g.text(font, mode, left + inner - font.width(mode), cy, layers.serverMode() ? 0xFF7CD67C : 0xFFE0C060);
		cy += 14;

		// Outils (2 rangées de 3)
		section(g, font, "Outil", left, cy);
		cy += 11;
		DrawTools.Tool[] all = DrawTools.Tool.values();
		int w3 = (inner - 4) / 3;

		for (int i = 0; i < all.length; i++) {
			DrawTools.Tool t = all[i];
			int bx = left + (i % 3) * (w3 + 2);
			int by = cy + (i / 3) * (BTN_H + 2);
			button(g, font, mouseX, mouseY, bx, by, w3, t.shortLabel, true, tools.tool() == t, () -> tools.setTool(t));
		}

		cy += ((all.length + 2) / 3) * (BTN_H + 2) + 2;
		button(g, font, mouseX, mouseY, left, cy, inner, "Formes : " + (tools.filled() ? "surface teintée" : "contour seul"), true, false,
				() -> tools.setFilled(!tools.filled()));
		cy += ROW + 4;

		// Zone sélectionnée
		LayerManager.ZoneRef sel = tools.selected();

		if (sel != null) {
			cy = zoneSection(g, font, mouseX, mouseY, sel, left, cy, inner);
		}

		// Couleur
		section(g, font, "Couleur", left, cy);
		cy += 11;

		for (int i = 0; i < DrawTools.PALETTE.length; i++) {
			int rgb = DrawTools.PALETTE[i];
			int sx = left + (i % 6) * (SWATCH + 3);
			int sy = cy + (i / 6) * (SWATCH + 3);

			if (rgb == tools.color()) {
				g.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy + SWATCH + 1, 0xFFFFFFFF);
			}

			g.fill(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF000000 | rgb);
			hits.add(new Hit(sx, sy, sx + SWATCH, sy + SWATCH, () -> {
				setColor(rgb);
				hexShownFor = -1;
			}));
		}

		cy += 2 * (SWATCH + 3) + 2;
		g.fill(left, cy, left + 16, cy + 16, 0xFF000000 | tools.color());
		textField(g, font, hexField, left + 20, cy, 70, mouseX, mouseY);
		cy += ROW + 4;

		// Épaisseur
		section(g, font, "Épaisseur", left, cy);
		cy += 11;
		button(g, font, mouseX, mouseY, left, cy, 18, "-", tools.width() > 1, false, () -> tools.setWidth(tools.width() - 1));
		String wt = tools.width() + (tools.width() > 1 ? " blocs" : " bloc");
		g.centeredText(font, wt, left + inner / 2, cy + 4, TEXT);
		button(g, font, mouseX, mouseY, left + inner - 18, cy, 18, "+", tools.width() < 5, false, () -> tools.setWidth(tools.width() + 1));
		cy += ROW;

		int w2 = (inner - 2) / 2;
		button(g, font, mouseX, mouseY, left, cy, w2, "Annuler", layers.canUndo(), false, layers::undo);
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Rétablir", layers.canRedo(), false, layers::redo);
		cy += ROW + 4;

		// Calques
		String dim = MapDrawClient.currentDimension();
		int colon = dim.indexOf(':');
		section(g, font, "Calques (" + (colon >= 0 ? dim.substring(colon + 1) : dim) + ")", left, cy);
		cy += 11;
		List<LayerData> list = layers.inDimension(dim);

		if (list.isEmpty()) {
			g.text(font, "Aucun calque : dessinez !", left + 2, cy + 2, TEXT_DIM);
			cy += 14;
		}

		for (LayerData layer : list) {
			cy = layerRow(g, font, mouseX, mouseY, layer, left, cy, inner);
		}

		cy += 4;
		textField(g, font, nameField, left, cy, inner, mouseX, mouseY);
		cy += ROW;

		LayerData active = layers.active();
		boolean hasActive = active != null;
		button(g, font, mouseX, mouseY, left, cy, w2, "Nouveau", true, false, this::newLayer);
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Renommer", hasActive && !nameField.value.isBlank(), false, this::renameLayer);
		cy += ROW;
		button(g, font, mouseX, mouseY, left, cy, inner,
				"Calque dans le monde : " + (hasActive && layers.isWorldHidden(active.meta().id()) ? "caché" : "visible"),
				hasActive, false, () -> layers.toggleWorld(active.meta().id()));
		cy += ROW;
		boolean canShare = hasActive && layers.serverMode() && layers.isOwn(active);
		button(g, font, mouseX, mouseY, left, cy, w2, hasActive && active.meta().shared() ? "Rendre privé" : "Partager", canShare, false,
				() -> layers.setShared(active.meta().id(), !active.meta().shared()));
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Supprimer", hasActive && layers.canEdit(active), false, this::deleteLayer);
		cy += ROW;
		button(g, font, mouseX, mouseY, left, cy, w2, "Exporter", hasActive, false, this::exportLayer);
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Importer", true, false,
				() -> open(new ImportScreen(host.get())));
		cy += ROW;
		button(g, font, mouseX, mouseY, left, cy, w2, "Tout effacer", hasActive && layers.canEdit(active), false, this::clearLayer);
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Réglages", true, false,
				() -> open(new ConfigScreen(host.get())));
		cy += ROW + 4;

		// Affichage
		section(g, font, "Affichage", left, cy);
		cy += 11;
		button(g, font, mouseX, mouseY, left, cy, inner, "Tracés dans le monde : " + (ClientConfig.get().showInWorld ? "ON" : "OFF"), true, false, () -> {
			ClientConfig cfg = ClientConfig.get();
			cfg.showInWorld = !cfg.showInWorld;
			cfg.save();
		});
		cy += ROW;
		button(g, font, mouseX, mouseY, left, cy, inner, "Fermer le panneau", true, false, onClose);
		cy += ROW + PAD;

		contentHeight = (int) (cy + scroll) - y;
		g.disableScissor();
		drawScrollbar(g);
	}

	/** Réglages de la zone sélectionnée. */
	private int zoneSection(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, LayerManager.ZoneRef sel, int left, int cy, int inner) {
		Zone z = sel.zone();
		boolean edit = layers.canEdit(sel.layer());
		int w2 = (inner - 2) / 2;
		g.fill(left - 2, cy - 2, left + inner + 2, cy + 9, 0x30FFFFFF);
		section(g, font, "Zone : " + z.kind().label + (edit ? "" : " (lecture seule)"), left, cy);
		g.fill(left + inner - 10, cy, left + inner, cy + 8, 0xFF000000 | z.color());
		cy += 12;
		g.text(font, fit(font, (z.maxX() - z.minX() + 1) + " × " + (z.maxZ() - z.minZ() + 1) + " blocs · " + sel.layer().meta().name(), inner),
				left, cy, TEXT_DIM);
		cy += 11;

		button(g, font, mouseX, mouseY, left, cy, inner, "Intérieur : " + (z.filled() ? "rempli" : "vide"), edit, z.filled(),
				() -> tools.updateSelected(zone -> zone.withFilled(!zone.filled())));
		cy += ROW;

		String op = z.opacity() == 0 ? "auto" : z.opacity() + " %";
		button(g, font, mouseX, mouseY, left, cy, 18, "-", edit && z.opacity() > 0, false,
				() -> tools.updateSelected(zone -> zone.withOpacity(Math.max(0, zone.opacity() - 10))));
		g.centeredText(font, "Opacité : " + op, left + inner / 2, cy + 5, TEXT);
		button(g, font, mouseX, mouseY, left + inner - 18, cy, 18, "+", edit && z.opacity() < 100, false,
				() -> tools.updateSelected(zone -> zone.withOpacity(Math.min(100, zone.opacity() + 10))));
		cy += ROW;

		button(g, font, mouseX, mouseY, left, cy, 18, "-", edit && z.width() > 1, false,
				() -> tools.updateSelected(zone -> zone.withWidth(zone.width() - 1)));
		g.centeredText(font, "Contour : " + z.width(), left + inner / 2, cy + 5, TEXT);
		button(g, font, mouseX, mouseY, left + inner - 18, cy, 18, "+", edit && z.width() < 5, false,
				() -> tools.updateSelected(zone -> zone.withWidth(zone.width() + 1)));
		cy += ROW;

		button(g, font, mouseX, mouseY, left, cy, w2, "Carte : " + (z.showMap() ? "oui" : "non"), edit, z.showMap(),
				() -> tools.updateSelected(zone -> zone.withShowMap(!zone.showMap())));
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Monde : " + (z.showWorld() ? "oui" : "non"), edit, z.showWorld(),
				() -> tools.updateSelected(zone -> zone.withShowWorld(!zone.showWorld())));
		cy += ROW;

		button(g, font, mouseX, mouseY, left, cy, inner, "Appliquer la couleur choisie", edit && z.color() != tools.color(), false,
				() -> tools.updateSelected(zone -> zone.withColor(tools.color())));
		cy += ROW;

		button(g, font, mouseX, mouseY, left, cy, w2, "Désélect.", true, false, tools::deselect);
		button(g, font, mouseX, mouseY, left + w2 + 2, cy, w2, "Supprimer", edit, false, tools::deleteSelected);
		cy += ROW;

		if (tools.tool() != DrawTools.Tool.SELECT) {
			button(g, font, mouseX, mouseY, left, cy, inner, "Déplacer (outil Sélect.)", true, false,
					() -> tools.setTool(DrawTools.Tool.SELECT));
			cy += ROW;
		}

		return cy + 4;
	}

	/** Nouvelle couleur ; avec l'outil Sélection, elle s'applique aussi à la zone choisie. */
	private void setColor(int rgb) {
		tools.setColor(rgb);

		if (tools.tool() == DrawTools.Tool.SELECT && tools.canEditSelected()) {
			tools.updateSelected(zone -> zone.withColor(rgb));
		}
	}

	private int layerRow(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, LayerData layer, int left, int cy, int inner) {
		int rowH = 13;
		boolean selected = layer.meta().id().equals(layers.activeId());
		boolean hovered = mouseX >= left && mouseX < left + inner && mouseY >= cy && mouseY < cy + rowH && contains(mouseX, mouseY);

		if (selected) {
			g.fill(left, cy, left + inner, cy + rowH, 0x60FFFFFF);
		} else if (hovered) {
			g.fill(left, cy, left + inner, cy + rowH, 0x30FFFFFF);
		}

		boolean hidden = layers.isHidden(layer.meta().id());
		g.text(font, hidden ? "○" : "●", left + 2, cy + 3, hidden ? TEXT_DIM : 0xFF7CD67C);
		hits.add(new Hit(left, cy, left + 12, cy + rowH, () -> layers.toggleHidden(layer.meta().id())));

		String badge = layer.meta().shared() ? (layers.isOwn(layer) ? "P" : layer.meta().ownerName()) : "";
		int badgeW = badge.isEmpty() ? 0 : font.width(badge) + 4;
		String label = layer.meta().name() + (layers.isWorldHidden(layer.meta().id()) ? " (carte)" : "");
		g.text(font, fit(font, label, inner - 14 - badgeW), left + 13, cy + 3, layers.canEdit(layer) ? TEXT : TEXT_DIM);

		if (!badge.isEmpty()) {
			g.text(font, badge, left + inner - font.width(badge) - 2, cy + 3, layers.isOwn(layer) ? 0xFF7FB2FF : 0xFFFFB060);
		}

		hits.add(new Hit(left + 12, cy, left + inner, cy + rowH, () -> {
			layers.setActive(layer.meta().id());
			nameField.value = layer.meta().name();
		}));
		return cy + rowH;
	}

	private void drawScrollbar(GuiGraphicsExtractor g) {
		if (contentHeight <= height) {
			return;
		}

		int trackX = x + WIDTH - SCROLLBAR_W - 2;
		int thumbH = Math.max(20, height * height / contentHeight);
		int thumbY = y + (int) ((height - thumbH) * (scroll / (contentHeight - height)));
		g.fill(trackX, y + 2, trackX + SCROLLBAR_W, y + height - 2, 0x40FFFFFF);
		g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, draggingScrollbar ? 0xFFFFFFFF : 0xFFB0B0B0);
	}

	private static void section(GuiGraphicsExtractor g, Font font, String title, int x, int y) {
		g.text(font, title, x, y, 0xFFE0C060);
	}

	private void button(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, int bx, int by, int w, String label,
			boolean enabled, boolean selected, Runnable action) {
		boolean hovered = enabled && mouseX >= bx && mouseX < bx + w && mouseY >= by && mouseY < by + BTN_H && contains(mouseX, mouseY);
		int bg = !enabled ? 0xFF222222 : selected ? 0xFF2E6B3A : hovered ? 0xFF505050 : 0xFF363636;
		g.fill(bx, by, bx + w, by + BTN_H, bg);
		g.outline(bx, by, w, BTN_H, selected ? 0xFF7CD67C : 0xFF555555);
		String text = fit(font, label, w - 6);
		g.text(font, text, bx + (w - font.width(text)) / 2, by + 5, enabled ? TEXT : 0xFF6A6A6A);

		if (enabled) {
			hits.add(new Hit(bx, by, bx + w, by + BTN_H, action));
		}
	}

	private void textField(GuiGraphicsExtractor g, Font font, TextField field, int fx, int fy, int w, int mouseX, int mouseY) {
		g.fill(fx, fy, fx + w, fy + 16, 0xFF000000);
		g.outline(fx, fy, w, 16, field.focused ? 0xFFFFFFFF : 0xFF707070);
		String shown = field.value;

		while (font.width(shown) > w - 8 && !shown.isEmpty()) {
			shown = shown.substring(1);
		}

		boolean blink = field.focused && (System.currentTimeMillis() / 500) % 2 == 0;

		if (field.value.isEmpty() && !field.focused) {
			g.text(font, field == nameField ? "Nom du calque…" : "#RRGGBB", fx + 4, fy + 4, 0xFF6A6A6A);
		} else {
			g.text(font, shown + (blink ? "_" : ""), fx + 4, fy + 4, TEXT);
		}

		hits.add(new Hit(fx, fy, fx + w, fy + 16, () -> {
			hexField.focused = field == hexField;
			nameField.focused = field == nameField;
		}));
	}

	private static String fit(Font font, String s, int maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}

		String out = s;

		while (!out.isEmpty() && font.width(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}

		return out + "…";
	}

	// ------------------------------------------------------------------ entrées

	/** @return true si le clic est dans le panneau (il est alors consommé). */
	public boolean mouseClicked(double mx, double my, int button) {
		if (!contains(mx, my)) {
			unfocus();
			return false;
		}

		if (button != SDLMouse.SDL_BUTTON_LEFT) {
			return true;
		}

		if (contentHeight > height && mx >= x + WIDTH - SCROLLBAR_W - 3) {
			draggingScrollbar = true;
			scrollTo(my);
			return true;
		}

		boolean hitField = false;

		for (Hit hit : new ArrayList<>(hits)) {
			if (hit.contains(mx, my)) {
				hit.action().run();
				hitField = hexField.focused || nameField.focused;
				break;
			}
		}

		if (!hitField) {
			unfocus();
		}

		return true;
	}

	public boolean mouseDragged(double mx, double my) {
		if (draggingScrollbar) {
			scrollTo(my);
			return true;
		}

		return false;
	}

	public boolean mouseReleased() {
		if (draggingScrollbar) {
			draggingScrollbar = false;
			return true;
		}

		return false;
	}

	private void scrollTo(double my) {
		double ratio = (my - y) / Math.max(1, height);
		scroll = ratio * (contentHeight - height);
	}

	public boolean mouseScrolled(double mx, double my, double vertical) {
		if (!contains(mx, my)) {
			return false;
		}

		scroll -= vertical * 24;
		return true;
	}

	/** @return true si la touche a été utilisée par un champ de saisie. */
	public boolean keyPressed(KeyEvent event) {
		TextField field = hexField.focused ? hexField : nameField.focused ? nameField : null;

		if (field == null) {
			return false;
		}

		if (Keys.isEscape(event)) {
			unfocus();
		} else if (Keys.isBackspace(event)) {
			if (!field.value.isEmpty()) {
				field.value = field.value.substring(0, field.value.length() - 1);
				onFieldChanged(field);
			}
		} else if (Keys.isEnter(event)) {
			if (field == nameField && !field.value.isBlank()) {
				LayerData active = layers.active();

				if (active != null) {
					layers.rename(active.meta().id(), field.value);
				} else {
					newLayer();
				}
			}

			unfocus();
		}

		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		TextField field = hexField.focused ? hexField : nameField.focused ? nameField : null;

		if (field == null) {
			return false;
		}

		if (event.isAllowedChatCharacter() && field.value.length() < field.maxLength) {
			field.value += event.codepointAsString();
			onFieldChanged(field);
		}

		return true;
	}

	private void onFieldChanged(TextField field) {
		if (field != hexField) {
			return;
		}

		String v = field.value.startsWith("#") ? field.value.substring(1) : field.value;

		if (v.length() == 6) {
			try {
				setColor(Integer.parseInt(v, 16));
				hexShownFor = tools.color();
			} catch (NumberFormatException ignored) {
				// saisie invalide
			}
		}
	}

	private void unfocus() {
		hexField.focused = false;
		nameField.focused = false;
		hexShownFor = -1;
	}

	// ------------------------------------------------------------------ actions

	private void open(Screen screen) {
		Minecraft.getInstance().gui.setScreen(screen);
	}

	private void newLayer() {
		String dim = MapDrawClient.currentDimension();
		String name = nameField.value.isBlank() ? "Calque " + (layers.inDimension(dim).size() + 1) : nameField.value;
		layers.createLayer(name, dim);
		nameField.value = "";
	}

	private void renameLayer() {
		LayerData active = layers.active();

		if (active != null && !nameField.value.isBlank()) {
			layers.rename(active.meta().id(), nameField.value);
		}
	}

	private void deleteLayer() {
		LayerData active = layers.active();

		if (active != null) {
			open(new ConfirmScreen(host.get(),
					"Supprimer le calque « " + active.meta().name() + " » ?",
					active.meta().shared() ? "Il sera supprimé pour tous les joueurs." : "Cette action est définitive.",
					() -> layers.delete(active.meta().id())));
		}
	}

	private void clearLayer() {
		LayerData active = layers.active();

		if (active != null) {
			open(new ConfirmScreen(host.get(),
					"Tout effacer sur « " + active.meta().name() + " » ?",
					active.size() + " bloc(s) et " + active.zoneCount() + " zone(s) seront effacés (Ctrl+Z pour annuler).",
					() -> layers.clearLayer(active.meta().id())));
		}
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
}
