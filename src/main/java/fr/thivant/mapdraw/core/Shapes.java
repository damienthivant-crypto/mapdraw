package fr.thivant.mapdraw.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rastérisation des formes sur la grille des blocs : rectangle, ellipse, polygone.
 * Chaque forme fournit son intérieur (cases dont le centre est dans la forme) et son contour épais.
 */
public final class Shapes {
	/** Surface maximale d'une forme remplie (protège le serveur et le rendu). */
	public static final int MAX_FILL = 512 * 512;

	private Shapes() {
	}

	/** Résultat d'une forme : contour et intérieur (l'intérieur exclut le contour). */
	public record Shape(Set<Long> outline, Set<Long> interior) {
		public int size() {
			return outline.size() + interior.size();
		}
	}

	// ------------------------------------------------------------------ rectangle

	public static Shape rectangle(int x0, int z0, int x1, int z1, int width, boolean filled) {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);
		Set<Long> outline = new LinkedHashSet<>();
		outline.addAll(Raster.thickLine(minX, minZ, maxX, minZ, width));
		outline.addAll(Raster.thickLine(maxX, minZ, maxX, maxZ, width));
		outline.addAll(Raster.thickLine(maxX, maxZ, minX, maxZ, width));
		outline.addAll(Raster.thickLine(minX, maxZ, minX, minZ, width));
		Set<Long> interior = new LinkedHashSet<>();

		if (filled) {
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					long k = CellKey.pack(x, z);

					if (!outline.contains(k)) {
						interior.add(k);
					}
				}
			}
		}

		return new Shape(outline, interior);
	}

	public static long rectangleArea(int x0, int z0, int x1, int z1) {
		return (long) (Math.abs(x1 - x0) + 1) * (Math.abs(z1 - z0) + 1);
	}

	// ------------------------------------------------------------------ ellipse

	/** Ellipse inscrite dans le rectangle (x0, z0)-(x1, z1). */
	public static Shape ellipse(int x0, int z0, int x1, int z1, int width, boolean filled) {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);
		double cx = (minX + maxX + 1) / 2.0;
		double cz = (minZ + maxZ + 1) / 2.0;
		double rx = (maxX - minX + 1) / 2.0;
		double rz = (maxZ - minZ + 1) / 2.0;
		Set<Long> inside = new LinkedHashSet<>();

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				double dx = (x + 0.5 - cx) / rx;
				double dz = (z + 0.5 - cz) / rz;

				if (dx * dx + dz * dz <= 1.0) {
					inside.add(CellKey.pack(x, z));
				}
			}
		}

		// Contour : cases intérieures touchant l'extérieur (y compris en diagonale), ce qui donne
		// un contour continu par les faces, sans trou dans les diagonales. Puis épaissi vers l'intérieur.
		Set<Long> border = new LinkedHashSet<>();

		for (long k : inside) {
			int x = CellKey.x(k);
			int z = CellKey.z(k);
			boolean edge = false;

			for (int dx = -1; dx <= 1 && !edge; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if ((dx != 0 || dz != 0) && !inside.contains(CellKey.pack(x + dx, z + dz))) {
						edge = true;
						break;
					}
				}
			}

			if (edge) {
				border.add(k);
			}
		}

		Set<Long> outline = thicken(border, inside, width);
		Set<Long> interior = new LinkedHashSet<>();

		if (filled) {
			for (long k : inside) {
				if (!outline.contains(k)) {
					interior.add(k);
				}
			}
		}

		return new Shape(outline, interior);
	}

	/** Épaissit un contour vers l'intérieur de la forme (épaisseur 1 = contour seul). */
	private static Set<Long> thicken(Set<Long> border, Set<Long> inside, int width) {
		Set<Long> out = new LinkedHashSet<>(border);
		Set<Long> frontier = border;

		for (int i = 1; i < Raster.clampWidth(width); i++) {
			Set<Long> next = new LinkedHashSet<>();

			for (long k : frontier) {
				int x = CellKey.x(k);
				int z = CellKey.z(k);

				for (long n : new long[] {CellKey.pack(x + 1, z), CellKey.pack(x - 1, z), CellKey.pack(x, z + 1), CellKey.pack(x, z - 1)}) {
					if (inside.contains(n) && out.add(n)) {
						next.add(n);
					}
				}
			}

			frontier = next;
		}

		return out;
	}

	// ------------------------------------------------------------------ polygone

	/** Polygone fermé défini par ses sommets (clés de cases). */
	public static Shape polygon(List<Long> vertices, int width, boolean filled) {
		Set<Long> outline = new LinkedHashSet<>();
		int n = vertices.size();

		for (int i = 0; i < n; i++) {
			long a = vertices.get(i);
			long b = vertices.get((i + 1) % n);
			outline.addAll(Raster.thickLine(CellKey.x(a), CellKey.z(a), CellKey.x(b), CellKey.z(b), width));
		}

		Set<Long> interior = new LinkedHashSet<>();

		if (filled && n >= 3) {
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxZ = Integer.MIN_VALUE;
			double[] px = new double[n];
			double[] pz = new double[n];

			for (int i = 0; i < n; i++) {
				px[i] = CellKey.x(vertices.get(i)) + 0.5;
				pz[i] = CellKey.z(vertices.get(i)) + 0.5;
				minX = Math.min(minX, CellKey.x(vertices.get(i)));
				maxX = Math.max(maxX, CellKey.x(vertices.get(i)));
				minZ = Math.min(minZ, CellKey.z(vertices.get(i)));
				maxZ = Math.max(maxZ, CellKey.z(vertices.get(i)));
			}

			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					long k = CellKey.pack(x, z);

					if (!outline.contains(k) && inside(px, pz, x + 0.5, z + 0.5)) {
						interior.add(k);
					}
				}
			}
		}

		return new Shape(outline, interior);
	}

	/** Point dans polygone (règle pair-impair). */
	private static boolean inside(double[] px, double[] pz, double x, double z) {
		boolean in = false;

		for (int i = 0, j = px.length - 1; i < px.length; j = i++) {
			if ((pz[i] > z) != (pz[j] > z) && x < (px[j] - px[i]) * (z - pz[i]) / (pz[j] - pz[i]) + px[i]) {
				in = !in;
			}
		}

		return in;
	}

	/** Surface approximative de la boîte englobante (pour refuser les formes énormes avant de les calculer). */
	public static long boundingArea(List<Long> vertices) {
		if (vertices.isEmpty()) {
			return 0;
		}

		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (long v : vertices) {
			minX = Math.min(minX, CellKey.x(v));
			maxX = Math.max(maxX, CellKey.x(v));
			minZ = Math.min(minZ, CellKey.z(v));
			maxZ = Math.max(maxZ, CellKey.z(v));
		}

		return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
	}
}
