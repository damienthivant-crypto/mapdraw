package fr.thivant.mapdraw.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.Raster;
import fr.thivant.mapdraw.core.Shapes;

/**
 * Outils de dessin partagés par l'écran MapDraw et l'intégration JourneyMap.
 * Les coordonnées reçues sont des coordonnées de bloc entières (X, Z).
 */
public final class DrawTools {
	public enum Tool {
		FREEHAND("Main levée", "Main"),
		LINE("Ligne droite", "Ligne"),
		ERASER("Gomme", "Gomme"),
		RECTANGLE("Rectangle", "Rect."),
		ELLIPSE("Cercle / ellipse", "Cercle"),
		POLYGON("Polygone", "Polyg.");

		public final String label;
		public final String shortLabel;

		Tool(String label, String shortLabel) {
			this.label = label;
			this.shortLabel = shortLabel;
		}

		public boolean isShape() {
			return this == RECTANGLE || this == ELLIPSE || this == POLYGON;
		}
	}

	/** Palette rapide (RGB). */
	public static final int[] PALETTE = {
			0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1, 0x1E88E5,
			0x3949AB, 0x8E24AA, 0xD81B60, 0x6D4C41, 0xFFFFFF, 0x212121
	};

	/** Au-delà, l'aperçu ne montre que le contour (pour rester fluide). */
	private static final long PREVIEW_FILL_LIMIT = 40_000;

	private final LayerManager layers;
	private Tool tool = Tool.FREEHAND;
	private boolean stroking;
	private int lastX;
	private int lastZ;
	/** Point de départ (ligne) ou coin (rectangle, ellipse). */
	private Long anchor;
	private final List<Long> polygon = new ArrayList<>();

	public DrawTools(LayerManager layers) {
		this.layers = layers;
	}

	public Tool tool() {
		return tool;
	}

	public void setTool(Tool tool) {
		endStroke();
		this.tool = tool;
		this.anchor = null;
		this.polygon.clear();
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

	/** Formes : intérieur teinté (surface) ou contour seul. */
	public boolean filled() {
		return ClientConfig.get().fillShapes;
	}

	public void setFilled(boolean filled) {
		ClientConfig.get().fillShapes = filled;
		ClientConfig.get().save();
	}

	public Long lineStart() {
		return anchor;
	}

	public List<Long> polygonPoints() {
		return Collections.unmodifiableList(polygon);
	}

	/** Une forme ou une ligne est en cours de construction. */
	public boolean hasPending() {
		return anchor != null || !polygon.isEmpty();
	}

	public boolean isStroking() {
		return stroking;
	}

	/** Consigne affichée pendant la construction d'une forme, ou null. */
	public String hint() {
		return switch (tool) {
			case LINE -> anchor != null ? "Ligne : cliquez le point d'arrivée (clic droit : annuler)" : null;
			case RECTANGLE, ELLIPSE -> anchor != null ? "Cliquez le coin opposé (clic droit : annuler)" : null;
			case POLYGON -> polygon.isEmpty() ? null
					: "Polygone : " + polygon.size() + " point(s) – cliquez le 1er point ou clic droit pour fermer, Retour arrière : enlever un point";
			default -> null;
		};
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

		long here = CellKey.pack(x, z);

		switch (tool) {
			case LINE -> {
				if (anchor == null) {
					anchor = here;
				} else {
					layers.beginAction(layer.meta().id());
					layers.paint(Raster.thickLine(CellKey.x(anchor), CellKey.z(anchor), x, z, width()), CellValue.line(color()));
					layers.endAction();
					anchor = null;
				}
			}
			case RECTANGLE, ELLIPSE -> {
				if (anchor == null) {
					anchor = here;
				} else {
					try {
						commit(layer, shapeTo(x, z, filled()));
					} catch (Shapes.TooLargeException e) {
						tooLarge();
					}

					anchor = null;
				}
			}
			case POLYGON -> {
				if (polygon.size() >= 3 && near(polygon.get(0), x, z)) {
					finishPolygon(dimension);
				} else if (polygon.isEmpty() || polygon.get(polygon.size() - 1) != here) {
					polygon.add(here);
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

	private static boolean near(long point, int x, int z) {
		return Math.abs(CellKey.x(point) - x) <= 1 && Math.abs(CellKey.z(point) - z) <= 1;
	}

	/** Ferme et trace le polygone en cours (clic droit ou Entrée). */
	public boolean finishPolygon(String dimension) {
		if (tool != Tool.POLYGON || polygon.size() < 3) {
			return false;
		}

		LayerData layer = layers.ensureEditableActive(dimension);

		if (layer != null) {
			try {
				commit(layer, Shapes.polygon(new ArrayList<>(polygon), width(), filled()));
			} catch (Shapes.TooLargeException e) {
				tooLarge();
			}
		}

		polygon.clear();
		return true;
	}

	/** Retire le dernier point du polygone. */
	public boolean removeLastPoint() {
		if (tool == Tool.POLYGON && !polygon.isEmpty()) {
			polygon.remove(polygon.size() - 1);
			return true;
		}

		return false;
	}

	private void tooLarge() {
		layers.info("Forme trop grande : " + Shapes.MAX_SPAN + " blocs de côté et " + Shapes.MAX_FILL + " blocs remplis au maximum.");
	}

	private Shapes.Shape shapeTo(int x, int z, boolean fill) throws Shapes.TooLargeException {
		int ax = CellKey.x(anchor);
		int az = CellKey.z(anchor);
		return tool == Tool.ELLIPSE
				? Shapes.ellipse(ax, az, x, z, width(), fill)
				: Shapes.rectangle(ax, az, x, z, width(), fill);
	}

	private void commit(LayerData layer, Shapes.Shape shape) {
		layers.beginAction(layer.meta().id());

		if (shape.interior().length > 0) {
			layers.paint(shape.interior(), CellValue.surface(color()));
		}

		layers.paint(shape.outline(), CellValue.line(color()));
		layers.endAction();
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

	/** Annule la ligne ou la forme en cours. */
	public boolean cancel() {
		if (hasPending()) {
			anchor = null;
			polygon.clear();
			return true;
		}

		return false;
	}

	/** Aperçu (case -> valeur) de la ligne ou de la forme en cours jusqu'au curseur, ou null. */
	public Map<Long, Integer> preview(int x, int z) {
		Map<Long, Integer> out = new LinkedHashMap<>();
		int line = CellValue.line(color());

		switch (tool) {
			case LINE -> {
				if (anchor == null) {
					return null;
				}

				for (long k : Raster.thickLine(CellKey.x(anchor), CellKey.z(anchor), x, z, width())) {
					out.put(k, line);
				}
			}
			case RECTANGLE, ELLIPSE -> {
				if (anchor == null) {
					return null;
				}

				// Aperçu léger : l'intérieur n'est montré que pour les petites formes.
				long box = (long) (Math.abs(x - CellKey.x(anchor)) + 1) * (Math.abs(z - CellKey.z(anchor)) + 1);
				Shapes.Shape shape;

				try {
					shape = shapeTo(x, z, filled() && box <= PREVIEW_FILL_LIMIT);
				} catch (Shapes.TooLargeException e) {
					return null;
				}

				for (long k : shape.interior()) {
					out.put(k, CellValue.surface(color()));
				}

				for (long k : shape.outline()) {
					out.put(k, line);
				}
			}
			case POLYGON -> {
				if (polygon.isEmpty()) {
					return null;
				}

				List<Long> pts = new ArrayList<>(polygon);
				pts.add(CellKey.pack(x, z));

				for (int i = 0; i + 1 < pts.size(); i++) {
					long a = pts.get(i);
					long b = pts.get(i + 1);

					for (long k : Raster.thickLine(CellKey.x(a), CellKey.z(a), CellKey.x(b), CellKey.z(b), width())) {
						out.put(k, line);
					}
				}
			}
			default -> {
				return null;
			}
		}

		return out;
	}

	private void apply(Set<Long> cells) {
		if (tool == Tool.ERASER) {
			layers.erase(cells);
		} else {
			layers.paint(cells, CellValue.line(color()));
		}
	}
}
