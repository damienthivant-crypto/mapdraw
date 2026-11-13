package fr.thivant.mapdraw.client.gui;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import fr.thivant.mapdraw.client.ClientConfig;
import fr.thivant.mapdraw.client.MapDrawClient;

/**
 * Réglages du mod (aussi accessible depuis Mod Menu).
 */
public final class ConfigScreen extends Screen {
	private final @Nullable Screen parent;

	public ConfigScreen(@Nullable Screen parent) {
		super(Component.literal("Réglages MapDraw"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ClientConfig cfg = ClientConfig.get();
		int cx = width / 2;
		int y = 40;

		toggle(cx, y, () -> "Affichage dans le monde : " + onOff(cfg.showInWorld), () -> cfg.showInWorld = !cfg.showInWorld);
		y += 24;
		stepper(cx, y, () -> "Opacité : " + Math.round(cfg.opacity * 100) + " %",
				() -> cfg.opacity = Math.max(0.1f, Math.round((cfg.opacity - 0.05f) * 100) / 100f),
				() -> cfg.opacity = Math.min(1.0f, Math.round((cfg.opacity + 0.05f) * 100) / 100f), true);
		y += 24;
		stepper(cx, y, () -> "Distance max : " + cfg.maxDistance + " blocs",
				() -> cfg.maxDistance = Math.max(16, cfg.maxDistance - 32),
				() -> cfg.maxDistance = Math.min(1024, cfg.maxDistance + 32), false);
		y += 24;
		toggle(cx, y, () -> "Visible à travers les blocs : " + onOff(cfg.seeThrough), () -> cfg.seeThrough = !cfg.seeThrough);
		y += 24;
		toggle(cx, y, () -> "Rendu : " + (cfg.renderMode == ClientConfig.RenderMode.GPU ? "GPU (rapide)" : "Compatibilité"),
				() -> {
					cfg.renderMode = cfg.renderMode == ClientConfig.RenderMode.GPU ? ClientConfig.RenderMode.COMPAT : ClientConfig.RenderMode.GPU;
					MapDrawClient.renderer().markAllGpuDirty();
				});
		y += 24;
		stepper(cx, y, () -> "Opacité sur JourneyMap : " + Math.round(cfg.journeyMapOpacity * 100) + " %",
				() -> cfg.journeyMapOpacity = Math.max(0.1f, Math.round((cfg.journeyMapOpacity - 0.1f) * 10) / 10f),
				() -> cfg.journeyMapOpacity = Math.min(1.0f, Math.round((cfg.journeyMapOpacity + 0.1f) * 10) / 10f), false);
		y += 32;
		addRenderableWidget(Button.builder(Component.literal("Terminé"), b -> onClose()).pos(cx - 100, y).size(200, 20).build());
	}

	private static String onOff(boolean b) {
		return b ? "ON" : "OFF";
	}

	private void toggle(int cx, int y, Supplier<String> label, Runnable action) {
		Button[] holder = new Button[1];
		holder[0] = addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
			action.run();
			ClientConfig.get().save();
			holder[0].setMessage(Component.literal(label.get()));
		}).pos(cx - 150, y).size(300, 20).build());
	}

	private void stepper(int cx, int y, Supplier<String> label, Runnable minus, Runnable plus, boolean rebuildMeshes) {
		Button[] text = new Button[1];
		Runnable after = () -> {
			ClientConfig.get().save();
			text[0].setMessage(Component.literal(label.get()));

			if (rebuildMeshes) {
				MapDrawClient.renderer().rebuildAll();
			}
		};
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			minus.run();
			after.run();
		}).pos(cx - 150, y).size(24, 20).build());
		text[0] = addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
		}).pos(cx - 124, y).size(248, 20).build());
		text[0].active = false;
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			plus.run();
			after.run();
		}).pos(cx + 126, y).size(24, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xD0000000);
		g.centeredText(font, "Réglages MapDraw", width / 2, 16, 0xFFFFFFFF);
		g.centeredText(font, "Blocs fantômes construits : " + MapDrawClient.renderer().ghostCount(), width / 2, height - 14, 0xFFA0A0A0);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// fond géré dans extractRenderState
	}

	@Override
	public void onClose() {
		ClientConfig.get().save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
