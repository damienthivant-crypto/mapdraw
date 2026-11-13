package fr.thivant.mapdraw.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.Raster;
import fr.thivant.mapdraw.core.Shapes;
import fr.thivant.mapdraw.core.Zone;

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
		POLYGON("Polygone", "Polyg."),
		SELECT("Sélection / déplacement de zone", "Sélect.");

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

	// Zone sélectionnée et déplacement en cours
	private UUID selLayer;
	private UUID selZone;
	private boolean moving;
	private int moveStartX;
	private int moveStartZ;
	private int moveDx;
	private int moveDz;
	/** Change à chaque modification de la sélection (pour rafraîchir les aperçus). */
	private int version;
	private Zone outlineFor;
	private long[] outlineCache = new long[0];
	/** Couleur de surbrillance de la zone sélectionnée. */
	public static final int HIGHLIGHT = 0xFFFFFF;

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
		version++;
	}

	// ------------------------------------------------------------------ sélection de zone

	public int version() {
		LayerManager.ZoneRef ref = selected();

		if (ref == null) {
			return version;
		}

		Zone z = ref.zone();
		return version * 31 + java.util.Objects.hash(z.color(), z.width(), z.filled(), z.showMap(), java.util.Arrays.hashCode(z.points()));
	}

	/** Zone sélectionnée (null si aucune ou si elle a disparu). */
	public LayerManager.ZoneRef selected() {
		if (selZone == null) {
			return null;
		}

		LayerManager.ZoneRef ref = layers.findZone(selLayer, selZone);

		if (ref == null) {
			selLayer = null;
			selZone = null;
			moving = false;
			version++;
		}

		return ref;
	}

	public boolean canEditSelected() {
		LayerManager.ZoneRef ref = selected();
		return ref != null && layers.canEdit(ref.layer());
	}

	public void select(LayerManager.ZoneRef ref) {
		selLayer = ref == null ? null : ref.layer().meta().id();
		selZone = ref == null ? null : ref.zone().id();
		moving = false;
		version++;
	}

	public boolean deselect() {
		if (selZone == null) {
			return false;
		}

		select(null);
		return true;
	}

	/** Modifie la zone sélectionnée (annulable). */
	public boolean updateSelected(UnaryOperator<Zone> change) {
		LayerManager.ZoneRef ref = selected();

		if (ref == null) {
			return false;
		}

		boolean ok = layers.putZone(ref.layer().meta().id(), change.apply(ref.zone()));
		version++;
		return ok;
	}

	public boolean deleteSelected() {
		LayerManager.ZoneRef ref = selected();

		if (ref == null) {
			return false;
		}

		boolean ok = layers.deleteZone(ref.layer().meta().id(), ref.zone().id());

		if (ok) {
			select(null);
		}

		return ok;
	}

	/** Décale la zone sélectionnée (flèches du clavier). */
	public boolean nudge(int dx, int dz) {
		return !moving && selected() != null && updateSelected(z -> z.translated(dx, dz));
	}

	public boolean isMoving() {
		return moving;
	}

	private long[] selectionOutline(Zone zone) {
		if (!zone.equals(outlineFor)) {
			outlineFor = zone;
			outlineCache = zone.outlineOnly();
		}

		return outlineCache;
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
		return anchor != null || !polygon.isEmpty() || moving;
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
			case SELECT -> moving ? "Déplacement : " + moveDx + ", " + moveDz + " blocs (clic droit : annuler)"
					: selZone != null ? "Glissez la zone pour la déplacer · flèches : 1 bloc · Suppr : supprimer"
					: "Cliquez une zone pour la sélectionner";
			default -> null;
		};
	}

	/**
	 * Clic gauche sur la carte.
	 *
	 * @return true si le clic a été utilisé par un outil
	 */
	public boolean press(String dimension, int x, int z) {
		if (tool == Tool.SELECT) {
			LayerManager.ZoneRef ref = layers.zoneAt(dimension, x, z);
			select(ref);

			if (ref != null) {
				if (layers.canEdit(ref.layer())) {
					moving = true;
					moveStartX = x;
					moveStartZ = z;
					moveDx = 0;
					moveDz = 0;
				} else {
					layers.info("Zone en lecture seule (calque de " + ref.layer().meta().ownerName() + ").");
				}
			}

			return true;
		}

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
					commitZone(layer, Zone.create(tool == Tool.ELLIPSE ? Zone.Kind.ELLIPSE : Zone.Kind.RECTANGLE,
							List.of(anchor, here), color(), width(), filled()));
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
			if (polygon.size() > Zone.MAX_POINTS) {
				layers.info("Polygone trop complexe (" + Zone.MAX_POINTS + " sommets max).");
			} else {
				commitZone(layer, Zone.create(Zone.Kind.POLYGON, new ArrayList<>(polygon), color(), width(), filled()));
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

	/** Crée la zone (vérifie sa taille) puis la sélectionne pour pouvoir la régler tout de suite. */
	private void commitZone(LayerData layer, Zone zone) {
		try {
			zone.shape();
		} catch (Shapes.TooLargeException e) {
			tooLarge();
			return;
		}

		if (layers.putZone(layer.meta().id(), zone)) {
			select(new LayerManager.ZoneRef(layer, zone));
		}
	}

	/** Glisser avec le bouton gauche enfoncé. */
	public boolean drag(int x, int z) {
		if (moving) {
			if (x - moveStartX != moveDx || z - moveStartZ != moveDz) {
				moveDx = x - moveStartX;
				moveDz = z - moveStartZ;
				version++;
			}

			return true;
		}

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
		if (moving) {
			moving = false;
			int dx = moveDx;
			int dz = moveDz;

			if (dx != 0 || dz != 0) {
				updateSelected(zone -> zone.translated(dx, dz));
			}

			version++;
		}

		if (stroking) {
			stroking = false;
			layers.endAction();
		}
	}

	/** Annule la ligne ou la forme en cours. */
	public boolean cancel() {
		if (moving) {
			moving = false;
			version++;
			return true;
		}

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
		LayerManager.ZoneRef sel = selected();

		// Surbrillance de la zone sélectionnée (déplacée si on est en train de la glisser)
		if (sel != null) {
			int dx = moving ? moveDx : 0;
			int dz = moving ? moveDz : 0;

			for (long k : selectionOutline(sel.zone())) {
				out.put(dx == 0 && dz == 0 ? k : CellKey.pack(CellKey.x(k) + dx, CellKey.z(k) + dz), CellValue.line(HIGHLIGHT));
			}
		}

		switch (tool) {
			case LINE -> {
				if (anchor == null) {
					return out.isEmpty() ? null : out;
				}

				for (long k : Raster.thickLine(CellKey.x(anchor), CellKey.z(anchor), x, z, width())) {
					out.put(k, line);
				}
			}
			case RECTANGLE, ELLIPSE -> {
				if (anchor == null) {
					return out.isEmpty() ? null : out;
				}

				// Aperçu léger : l'intérieur n'est montré que pour les petites formes.
				long box = (long) (Math.abs(x - CellKey.x(anchor)) + 1) * (Math.abs(z - CellKey.z(anchor)) + 1);
				Shapes.Shape shape;

				try {
					shape = shapeTo(x, z, filled() && box <= PREVIEW_FILL_LIMIT);
				} catch (Shapes.TooLargeException e) {
					return out.isEmpty() ? null : out;
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
					return out.isEmpty() ? null : out;
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
				return out.isEmpty() ? null : out;
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
