package fr.thivant.mapdraw.client.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import fr.thivant.mapdraw.client.ClientNet;
import fr.thivant.mapdraw.client.ClientStorage;
import fr.thivant.mapdraw.client.MapDrawClient;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerJson;

/**
 * Import d'un calque .json depuis {@code config/mapdraw/exports}.
 * Le calque importé devient un calque privé du joueur dans la dimension courante.
 */
public final class ImportScreen extends Screen {
	private static final int PER_PAGE = 8;
	private final Screen parent;
	private List<Path> files;
	private int page;

	public ImportScreen(Screen parent) {
		super(Component.literal("Importer un calque"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		files = ClientStorage.listImportable();
		int cx = width / 2;
		int y = 44;
		int start = page * PER_PAGE;

		for (int i = start; i < Math.min(files.size(), start + PER_PAGE); i++) {
			Path file = files.get(i);
			String name = file.getFileName().toString();
			addRenderableWidget(Button.builder(Component.literal(name.length() > 44 ? name.substring(0, 43) + "…" : name), b -> importFile(file))
					.pos(cx - 150, y).size(300, 20).build());
			y += 22;
		}

		int by = Math.max(y + 8, height - 32);

		if (page > 0) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> {
				page--;
				rebuildWidgets();
			}).pos(cx - 150, by).size(30, 20).build());
		}

		if ((page + 1) * PER_PAGE < files.size()) {
			addRenderableWidget(Button.builder(Component.literal(">"), b -> {
				page++;
				rebuildWidgets();
			}).pos(cx + 120, by).size(30, 20).build());
		}

		addRenderableWidget(Button.builder(Component.literal("Retour"), b -> onClose()).pos(cx - 50, by).size(100, 20).build());
	}

	private void importFile(Path file) {
		try {
			LayerData source = LayerJson.readSingleLayer(file);
			LayerData imported = MapDrawClient.layers().importLayer(source, MapDrawClient.currentDimension());
			ClientNet.chat("MapDraw : « " + imported.meta().name() + " » importé (" + imported.size() + " blocs).");
		} catch (IOException e) {
			ClientNet.chat("MapDraw : import impossible (" + e.getMessage() + ")");
		}

		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xD0000000);
		g.centeredText(font, "Importer un calque", width / 2, 12, 0xFFFFFFFF);
		g.centeredText(font, "Dossier : " + ClientStorage.exportDir().toAbsolutePath(), width / 2, 26, 0xFFA0A0A0);

		if (files != null && files.isEmpty()) {
			g.centeredText(font, "Aucun fichier .json dans ce dossier.", width / 2, 60, 0xFFE0C060);
		}

		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// fond géré dans extractRenderState
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
