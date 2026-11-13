package fr.thivant.mapdraw.core;

/**
 * Valeur stockée pour une case : couleur RGB sur 24 bits + drapeau « surface » (bit 24).
 * <ul>
 *   <li>trait : couleur pleine, bloc fantôme complet dans le monde ;</li>
 *   <li>surface : teinte translucide sur la carte, plaque fine posée sur le sol dans le monde.</li>
 * </ul>
 */
public final class CellValue {
	public static final int SURFACE = 1 << 24;
	/** Bits utiles (couleur + drapeaux). */
	public static final int MASK = 0x1FFFFFF;

	private CellValue() {
	}

	public static int rgb(int value) {
		return value & 0xFFFFFF;
	}

	public static boolean isSurface(int value) {
		return (value & SURFACE) != 0;
	}

	public static int line(int rgb) {
		return rgb & 0xFFFFFF;
	}

	public static int surface(int rgb) {
		return (rgb & 0xFFFFFF) | SURFACE;
	}
}
