package fr.thivant.mapdraw.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rastérisation des tracés sur la grille des blocs.
 * <p>
 * Les lignes utilisent Bresenham en version « 4-connexe » : à chaque pas diagonal, une case
 * intermédiaire est ajoutée, si bien que deux cases consécutives partagent toujours une arête.
 * Dans le monde, une diagonale donne donc une suite continue de blocs (aucun trou).
 */
public final class Raster {
	public static final int MIN_WIDTH = 1;
	public static final int MAX_WIDTH = 5;

	private Raster() {
	}

	public interface CellConsumer {
		void accept(int x, int z);
	}

	/** Ligne 4-connexe de (x0, z0) à (x1, z1), extrémités incluses. */
	public static void line(int x0, int z0, int x1, int z1, CellConsumer out) {
		int dx = Math.abs(x1 - x0);
		int dz = -Math.abs(z1 - z0);
		int sx = x0 < x1 ? 1 : -1;
		int sz = z0 < z1 ? 1 : -1;
		int err = dx + dz;
		int x = x0;
		int z = z0;
		out.accept(x, z);

		while (x != x1 || z != z1) {
			int e2 = 2 * err;
			boolean stepX = e2 >= dz;
			boolean stepZ = e2 <= dx;

			if (stepX && stepZ) {
				// Pas diagonal : on insère une case intermédiaire pour garder la continuité par arête.
				// On garde la case intermédiaire la plus proche de la droite idéale.
				long ddx = (long) x1 - x0;
				long ddz = (long) z1 - z0;
				long distA = Math.abs(ddx * ((long) z0 - z) - ((long) x0 - (x + sx)) * ddz);
				long distB = Math.abs(ddx * ((long) z0 - (z + sz)) - ((long) x0 - x) * ddz);

				if (distA <= distB) {
					out.accept(x + sx, z);
				} else {
					out.accept(x, z + sz);
				}
			}

			if (stepX) {
				err += dz;
				x += sx;
			}

			if (stepZ) {
				err += dx;
				z += sz;
			}

			out.accept(x, z);
		}
	}

	/** Bornes du pinceau carré pour une épaisseur donnée (1 à 5 blocs). */
	public static int brushLow(int width) {
		return -(clampWidth(width) - 1) / 2;
	}

	public static int brushHigh(int width) {
		return clampWidth(width) / 2;
	}

	public static int clampWidth(int width) {
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
	}

	/** Applique le pinceau carré (épaisseur en blocs) autour d'une case. */
	public static void brush(int cx, int cz, int width, Set<Long> out) {
		int lo = brushLow(width);
		int hi = brushHigh(width);

		for (int dx = lo; dx <= hi; dx++) {
			for (int dz = lo; dz <= hi; dz++) {
				out.add(CellKey.pack(cx + dx, cz + dz));
			}
		}
	}

	/** Toutes les cases couvertes par un segment épais. */
	public static Set<Long> thickLine(int x0, int z0, int x1, int z1, int width) {
		Set<Long> cells = new LinkedHashSet<>();
		line(x0, z0, x1, z1, (x, z) -> brush(x, z, width, cells));
		return cells;
	}
}
