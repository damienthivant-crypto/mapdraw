package fr.thivant.mapdraw.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Une zone : forme vectorielle (rectangle, ellipse, polygone) qu'on peut sélectionner, déplacer et régler.
 * Ses blocs sont recalculés à partir de la géométrie ; rien n'est « gravé » dans le calque.
 *
 * @param points    rectangle / ellipse : 2 coins ; polygone : les sommets (clés de cases)
 * @param opacity   opacité de la zone en % (0 = réglages généraux)
 * @param showWorld visible dans le monde
 * @param showMap   visible sur la carte
 */
public record Zone(UUID id, Kind kind, long[] points, int color, int width, boolean filled, int opacity,
		boolean showWorld, boolean showMap, String name) {
	public static final int MAX_POINTS = 512;

	public enum Kind {
		RECTANGLE("Rectangle"),
		ELLIPSE("Cercle"),
		POLYGON("Polygone");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	public Zone {
		color &= 0xFFFFFF;
		width = Raster.clampWidth(width);
		opacity = Math.max(0, Math.min(100, opacity));
		name = name == null ? "" : (name.length() > 48 ? name.substring(0, 48) : name);
		points = points.clone();
	}

	/** Géométrie acceptable (nombre de points, étendue) ? Utilisé aussi côté serveur. */
	public boolean valid() {
		if (kind == Kind.POLYGON ? (points.length < 3 || points.length > MAX_POINTS) : points.length != 2) {
			return false;
		}

		return (long) maxX() - minX() <= Shapes.MAX_SPAN && (long) maxZ() - minZ() <= Shapes.MAX_SPAN;
	}

	public static Zone create(Kind kind, List<Long> pts, int color, int width, boolean filled) {
		long[] arr = new long[pts.size()];

		for (int i = 0; i < arr.length; i++) {
			arr[i] = pts.get(i);
		}

		return new Zone(UUID.randomUUID(), kind, arr, color, width, filled, 0, true, true, "");
	}

	public List<Long> pointList() {
		List<Long> out = new ArrayList<>(points.length);

		for (long p : points) {
			out.add(p);
		}

		return out;
	}

	public Zone translated(int dx, int dz) {
		long[] moved = new long[points.length];

		for (int i = 0; i < points.length; i++) {
			moved[i] = CellKey.pack(CellKey.x(points[i]) + dx, CellKey.z(points[i]) + dz);
		}

		return new Zone(id, kind, moved, color, width, filled, opacity, showWorld, showMap, name);
	}

	public Zone withColor(int c) {
		return new Zone(id, kind, points, c, width, filled, opacity, showWorld, showMap, name);
	}

	public Zone withWidth(int w) {
		return new Zone(id, kind, points, color, w, filled, opacity, showWorld, showMap, name);
	}

	public Zone withFilled(boolean f) {
		return new Zone(id, kind, points, color, width, f, opacity, showWorld, showMap, name);
	}

	public Zone withOpacity(int o) {
		return new Zone(id, kind, points, color, width, filled, o, showWorld, showMap, name);
	}

	public Zone withShowWorld(boolean v) {
		return new Zone(id, kind, points, color, width, filled, opacity, v, showMap, name);
	}

	public Zone withShowMap(boolean v) {
		return new Zone(id, kind, points, color, width, filled, opacity, showWorld, v, name);
	}

	public Zone withName(String n) {
		return new Zone(id, kind, points, color, width, filled, opacity, showWorld, showMap, n);
	}

	/** Blocs de la zone (contour + intérieur). */
	public Shapes.Shape shape() throws Shapes.TooLargeException {
		return switch (kind) {
			case RECTANGLE -> Shapes.rectangle(CellKey.x(points[0]), CellKey.z(points[0]), CellKey.x(points[1]), CellKey.z(points[1]), width, filled);
			case ELLIPSE -> Shapes.ellipse(CellKey.x(points[0]), CellKey.z(points[0]), CellKey.x(points[1]), CellKey.z(points[1]), width, filled);
			case POLYGON -> Shapes.polygon(pointList(), width, filled);
		};
	}

	/** Contour seul (aperçus, surbrillance de sélection). */
	public long[] outlineOnly() {
		try {
			return withFilled(false).shape().outline();
		} catch (Shapes.TooLargeException e) {
			return new long[0];
		}
	}

	/** Flags communs à tous les blocs de la zone. */
	private int visibilityFlags() {
		return (showWorld ? 0 : CellValue.NO_WORLD) | (showMap ? 0 : CellValue.NO_MAP);
	}

	public int outlineValue() {
		return CellValue.withAlphaPercent(CellValue.line(color), opacity) | visibilityFlags();
	}

	public int interiorValue() {
		return CellValue.withAlphaPercent(CellValue.surface(color), opacity) | visibilityFlags();
	}

	public int minX() {
		int m = Integer.MAX_VALUE;

		for (long p : points) {
			m = Math.min(m, CellKey.x(p));
		}

		return m;
	}

	public int maxX() {
		int m = Integer.MIN_VALUE;

		for (long p : points) {
			m = Math.max(m, CellKey.x(p));
		}

		return m;
	}

	public int minZ() {
		int m = Integer.MAX_VALUE;

		for (long p : points) {
			m = Math.min(m, CellKey.z(p));
		}

		return m;
	}

	public int maxZ() {
		int m = Integer.MIN_VALUE;

		for (long p : points) {
			m = Math.max(m, CellKey.z(p));
		}

		return m;
	}

	/** Le bloc (x, z) fait-il partie de la zone (pour la sélection au clic) ? */
	public boolean contains(int x, int z) {
		int pad = width;

		if (x < minX() - pad || x > maxX() + pad || z < minZ() - pad || z > maxZ() + pad) {
			return false;
		}

		double px = x + 0.5;
		double pz = z + 0.5;

		switch (kind) {
			case RECTANGLE -> {
				return true;
			}
			case ELLIPSE -> {
				double cx = (minX() + maxX() + 1) / 2.0;
				double cz = (minZ() + maxZ() + 1) / 2.0;
				double rx = (maxX() - minX() + 1) / 2.0 + pad;
				double rz = (maxZ() - minZ() + 1) / 2.0 + pad;
				double dx = (px - cx) / rx;
				double dz = (pz - cz) / rz;
				return dx * dx + dz * dz <= 1.0;
			}
			default -> {
				int n = points.length;
				boolean in = false;

				for (int i = 0, j = n - 1; i < n; j = i++) {
					double xi = CellKey.x(points[i]) + 0.5;
					double zi = CellKey.z(points[i]) + 0.5;
					double xj = CellKey.x(points[j]) + 0.5;
					double zj = CellKey.z(points[j]) + 0.5;

					if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
						in = !in;
					}

					if (segmentDistance(px, pz, xi, zi, xj, zj) <= pad + 0.5) {
						return true;
					}
				}

				return in;
			}
		}
	}

	private static double segmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
		double dx = bx - ax;
		double dz = bz - az;
		double len = dx * dx + dz * dz;
		double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len));
		double cx = ax + t * dx - px;
		double cz = az + t * dz - pz;
		return Math.sqrt(cx * cx + cz * cz);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Zone z && z.id.equals(id) && z.kind == kind && java.util.Arrays.equals(z.points, points)
				&& z.color == color && z.width == width && z.filled == filled && z.opacity == opacity
				&& z.showWorld == showWorld && z.showMap == showMap && z.name.equals(name);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}
}
