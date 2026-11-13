package fr.thivant.mapdraw.client;

import java.util.Set;

import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.Raster;

/**
 * Outils de dessin partagés par l'écran MapDraw et l'intégration JourneyMap.
 * Les coordonnées reçues sont des coordonnées de bloc entières (X, Z).
 */
public final class DrawTools {
	public enum Tool {
		FREEHAND("Main levée"),
		LINE("Ligne droite"),
		ERASER("Gomme");

		public final String label;

		Tool(String label) {
			this.label = label;
		}
	}

	/** Palette rapide (RGB). */
	public static final int[] PALETTE = {
			0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1, 0x1E88E5,
			0x3949AB, 0x8E24AA, 0xD81B60, 0x6D4C41, 0xFFFFFF, 0x212121
	};

	private final LayerManager layers;
	private Tool tool = Tool.FREEHAND;
	private boolean stroking;
	private int lastX;
	private int lastZ;
	private Long lineStart;

	public DrawTools(LayerManager layers) {
		this.layers = layers;
	}

	public Tool tool() {
		return tool;
	}

	public void setTool(Tool tool) {
		endStroke();
		this.tool = tool;
		this.lineStart = null;
	}

	public int color() {
		return ClientConfig.get().color;
	}

	public void setColor(int rgb) {
		ClientConfig.get().color = rgb & 0xFFFFFF;
		ClientConfig.get().save();
	}

	public int width() {
		return ClientConfig.get().width;
	}

	public void setWidth(int width) {
		ClientConfig.get().width = Raster.clampWidth(width);
		ClientConfig.get().save();
	}

	public Long lineStart() {
		return lineStart;
	}

	public boolean isStroking() {
		return stroking;
	}

	/**
	 * Clic gauche sur la carte.
	 *
	 * @return true si le clic a été utilisé par un outil
	 */
	public boolean press(String dimension, int x, int z) {
		LayerData layer = layers.ensureEditableActive(dimension);

		if (layer == null) {
			return false;
		}

		switch (tool) {
			case LINE -> {
				if (lineStart == null) {
					lineStart = CellKey.pack(x, z);
				} else {
					layers.beginAction(layer.meta().id());
					layers.paint(Raster.thickLine(CellKey.x(lineStart), CellKey.z(lineStart), x, z, width()), color());
					layers.endAction();
					lineStart = null;
				}
			}
			case FREEHAND, ERASER -> {
				layers.beginAction(layer.meta().id());
				stroking = true;
				lastX = x;
				lastZ = z;
				apply(Raster.thickLine(x, z, x, z, width()));
			}
		}

		return true;
	}

	/** Glisser avec le bouton gauche enfoncé. */
	public boolean drag(int x, int z) {
		if (!stroking) {
			return false;
		}

		if (x != lastX || z != lastZ) {
			apply(Raster.thickLine(lastX, lastZ, x, z, width()));
			lastX = x;
			lastZ = z;
		}

		return true;
	}

	/** Relâchement du bouton : fin du trait (une seule entrée dans l'historique). */
	public void endStroke() {
		if (stroking) {
			stroking = false;
			layers.endAction();
		}
	}

	/** Annule une ligne commencée. */
	public boolean cancel() {
		if (lineStart != null) {
			lineStart = null;
			return true;
		}

		return false;
	}

	/** Cases de l'aperçu de la ligne en cours, ou null. */
	public Set<Long> linePreview(int x, int z) {
		if (tool != Tool.LINE || lineStart == null) {
			return null;
		}

		return Raster.thickLine(CellKey.x(lineStart), CellKey.z(lineStart), x, z, width());
	}

	private void apply(Set<Long> cells) {
		if (tool == Tool.ERASER) {
			layers.erase(cells);
		} else {
			layers.paint(cells, color());
		}
	}
}
