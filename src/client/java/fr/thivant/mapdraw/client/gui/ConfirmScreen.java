package fr.thivant.mapdraw.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Petite boîte de confirmation Oui / Non.
 */
public final class ConfirmScreen extends Screen {
	private final Screen parent;
	private final String line1;
	private final String line2;
	private final Runnable onYes;

	public ConfirmScreen(Screen parent, String line1, String line2, Runnable onYes) {
		super(Component.literal("Confirmation"));
		this.parent = parent;
		this.line1 = line1;
		this.line2 = line2;
		this.onYes = onYes;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 2 + 10;
		addRenderableWidget(Button.builder(Component.literal("Oui"), b -> {
			onYes.run();
			minecraft.gui.setScreen(parent);
		}).pos(cx - 104, y).size(100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Non"), b -> minecraft.gui.setScreen(parent)).pos(cx + 4, y).size(100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xC0000000);
		g.centeredText(font, line1, width / 2, height / 2 - 26, 0xFFFFFFFF);
		g.centeredText(font, line2, width / 2, height / 2 - 12, 0xFFC0C0C0);
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
