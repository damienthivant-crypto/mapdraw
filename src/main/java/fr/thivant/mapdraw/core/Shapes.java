package fr.thivant.mapdraw.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rastérisation des formes sur la grille des blocs : rectangle, ellipse, polygone.
 * <p>
 * Les intérieurs sont calculés ligne par ligne (balayage), sans tester chaque case contre chaque arête,
 * et renvoyés sous forme de tableaux primitifs : une forme de 1 000 x 1 000 blocs reste rapide.
 */
public final class Shapes {
	/** Nombre maximal de blocs d'une forme remplie. */
	public static final int MAX_FILL = 1_000_000;
	/** Côté maximal de la boîte englobante d'une forme. */
	public static final int MAX_SPAN = 4096;

	private Shapes() {
	}

	/** Contour (traits) et intérieur (surface), disjoints. */
	public record Shape(long[] outline, long[] interior) {
		public int size() {
			return outline.length + interior.length;
		}
	}

	/** Forme refusée car trop grande. */
	public static final class TooLargeException extends Exception {
		public TooLargeException() {
			super("Forme trop grande");
		}
	}

	private static long[] toArray(Set<Long> set) {
		long[] out = new long[set.size()];
		int i = 0;

		for (long k : set) {
			out[i++] = k;
		}

		return out;
	}

	private static LongIntMap toMap(long[] keys) {
		LongIntMap m = new LongIntMap(keys.length);

		for (long k : keys) {
			m.put(k, 1);
		}

		return m;
	}

	/** Liste de longs extensible (évite les objets Long). */
	private static final class LongList {
		long[] data = new long[256];
		int size;

		void add(long v) {
			if (size == data.length) {
				data = Arrays.copyOf(data, size * 2);
			}

			data[size++] = v;
		}

		long[] toArray() {
			return Arrays.copyOf(data, size);
		}
	}

	private static void checkBox(int minX, int maxX, int minZ, int maxZ) throws TooLargeException {
		if ((long) maxX - minX + 1 > MAX_SPAN || (long) maxZ - minZ + 1 > MAX_SPAN) {
			throw new TooLargeException();
		}
	}

	// ------------------------------------------------------------------ rectangle

	public static Shape rectangle(int x0, int z0, int x1, int z1, int width, boolean filled) throws TooLargeException {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);
		checkBox(minX, maxX, minZ, maxZ);
		Set<Long> outline = new LinkedHashSet<>();
		outline.addAll(Raster.thickLine(minX, minZ, maxX, minZ, width));
		outline.addAll(Raster.thickLine(maxX, minZ, maxX, maxZ, width));
		outline.addAll(Raster.thickLine(maxX, maxZ, minX, maxZ, width));
		outline.addAll(Raster.thickLine(minX, maxZ, minX, minZ, width));
		long[] interior = new long[0];

		if (filled) {
			// Le pinceau déborde de brushHigh vers l'intérieur : l'intérieur commence juste après.
			int hiB = Raster.brushHigh(width);
			int loB = Raster.brushLow(width);
			int ix0 = minX + hiB + 1;
			int ix1 = maxX + loB - 1;
			int iz0 = minZ + hiB + 1;
			int iz1 = maxZ + loB - 1;

			if (ix0 <= ix1 && iz0 <= iz1) {
				long count = (long) (ix1 - ix0 + 1) * (iz1 - iz0 + 1);

				if (count > MAX_FILL) {
					throw new TooLargeException();
				}

				interior = new long[(int) count];
				int n = 0;

				for (int z = iz0; z <= iz1; z++) {
					for (int x = ix0; x <= ix1; x++) {
						interior[n++] = CellKey.pack(x, z);
					}
				}
			}
		}

		return new Shape(toArray(outline), interior);
	}

	// ------------------------------------------------------------------ ellipse

	/** Ellipse inscrite dans le rectangle (x0, z0)-(x1, z1). */
	public static Shape ellipse(int x0, int z0, int x1, int z1, int width, boolean filled) throws TooLargeException {
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);
		checkBox(minX, maxX, minZ, maxZ);
		double cx = (minX + maxX + 1) / 2.0;
		double cz = (minZ + maxZ + 1) / 2.0;
		double rx = (maxX - minX + 1) / 2.0;
		double rz = (maxZ - minZ + 1) / 2.0;
		int rows = maxZ - minZ + 1;
		// Une seule plage [lo, hi] par ligne ; lo > hi = ligne vide.
		int[] lo = new int[rows];
		int[] hi = new int[rows];

		for (int i = 0; i < rows; i++) {
			double dz = (minZ + i + 0.5 - cz) / rz;
			double t = 1.0 - dz * dz;

			if (t < 0) {
				lo[i] = 1;
				hi[i] = 0;
				continue;
			}

			double half = rx * Math.sqrt(t);
			lo[i] = (int) Math.ceil(cx - half - 0.5);
			hi[i] = (int) Math.floor(cx + half - 0.5);
		}

		// Érosion 3x3 répétée « width » fois : ce qui reste est l'intérieur, le reste est le contour.
		int[] inLo = lo.clone();
		int[] inHi = hi.clone();

		for (int step = 0; step < Raster.clampWidth(width); step++) {
			int[] nLo = new int[rows];
			int[] nHi = new int[rows];

			for (int i = 0; i < rows; i++) {
				if (i == 0 || i == rows - 1 || inLo[i] > inHi[i] || inLo[i - 1] > inHi[i - 1] || inLo[i + 1] > inHi[i + 1]) {
					nLo[i] = 1;
					nHi[i] = 0;
					continue;
				}

				nLo[i] = Math.max(inLo[i], Math.max(inLo[i - 1], inLo[i + 1])) + 1;
				nHi[i] = Math.min(inHi[i], Math.min(inHi[i - 1], inHi[i + 1])) - 1;
			}

			inLo = nLo;
			inHi = nHi;
		}

		long interiorCount = 0;

		for (int i = 0; i < rows; i++) {
			if (inLo[i] <= inHi[i]) {
				interiorCount += inHi[i] - inLo[i] + 1;
			}
		}

		if (filled && interiorCount > MAX_FILL) {
			throw new TooLargeException();
		}

		LongList outline = new LongList();
		LongList interior = new LongList();

		for (int i = 0; i < rows; i++) {
			int z = minZ + i;

			for (int x = lo[i]; x <= hi[i]; x++) {
				boolean inner = inLo[i] <= inHi[i] && x >= inLo[i] && x <= inHi[i];

				if (!inner) {
					outline.add(CellKey.pack(x, z));
				} else if (filled) {
					interior.add(CellKey.pack(x, z));
				} else {
					x = inHi[i]; // saute l'intérieur
				}
			}
		}

		return new Shape(outline.toArray(), interior.toArray());
	}

	// ------------------------------------------------------------------ polygone

	/** Polygone fermé défini par ses sommets (clés de cases). */
	public static Shape polygon(List<Long> vertices, int width, boolean filled) throws TooLargeException {
		int n = vertices.size();
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

		checkBox(minX, maxX, minZ, maxZ);
		Set<Long> outlineSet = new LinkedHashSet<>();

		for (int i = 0; i < n; i++) {
			long a = vertices.get(i);
			long b = vertices.get((i + 1) % n);
			outlineSet.addAll(Raster.thickLine(CellKey.x(a), CellKey.z(a), CellKey.x(b), CellKey.z(b), width));
		}

		long[] outline = toArray(outlineSet);
		long[] interior = new long[0];

		if (filled && n >= 3) {
			LongIntMap onOutline = toMap(outline);
			double[] px = new double[n];
			double[] pz = new double[n];

			for (int i = 0; i < n; i++) {
				px[i] = CellKey.x(vertices.get(i)) + 0.5;
				pz[i] = CellKey.z(vertices.get(i)) + 0.5;
			}

			// Balayage : pour chaque ligne, intersections avec les arêtes, puis remplissage pair-impair.
			double[] xs = new double[n];
			LongList cells = new LongList();
			long count = 0;

			for (int z = minZ; z <= maxZ; z++) {
				double y = z + 0.5;
				int k = 0;

				for (int i = 0, j = n - 1; i < n; j = i++) {
					if ((pz[i] > y) != (pz[j] > y)) {
						xs[k++] = (px[j] - px[i]) * (y - pz[i]) / (pz[j] - pz[i]) + px[i];
					}
				}

				Arrays.sort(xs, 0, k);

				for (int p = 0; p + 1 < k; p += 2) {
					int xStart = (int) Math.ceil(xs[p] - 0.5);
					int xEnd = (int) Math.ceil(xs[p + 1] - 0.5) - 1;

					for (int x = xStart; x <= xEnd; x++) {
						long key = CellKey.pack(x, z);

						if (!onOutline.containsKey(key)) {
							if (++count > MAX_FILL) {
								throw new TooLargeException();
							}

							cells.add(key);
						}
					}
				}
			}

			interior = cells.toArray();
		}

		return new Shape(outline, interior);
	}
}
