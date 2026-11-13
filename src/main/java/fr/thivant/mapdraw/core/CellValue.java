package fr.thivant.mapdraw.core;

/**
 * Valeur stockée pour une case :
 * <ul>
 *   <li>bits 0-23 : couleur RGB ;</li>
 *   <li>bit 24 : surface (teinte translucide sur la carte, plaque au sol dans le monde) ;</li>
 *   <li>bits 25-28 : opacité propre à une zone (0 = réglage général, 1..15 = 1/15 .. 15/15) ;</li>
 *   <li>bit 29 : zone masquée dans le monde ; bit 30 : zone masquée sur la carte.</li>
 * </ul>
 * Les traits dessinés à la main n'utilisent que les bits 0-24 ({@link #MASK}).
 */
public final class CellValue {
	public static final int SURFACE = 1 << 24;
	/** Bits autorisés pour un trait (couleur + surface). */
	public static final int MASK = 0x1FFFFFF;
	private static final int ALPHA_SHIFT = 25;
	private static final int ALPHA_BITS = 0xF << ALPHA_SHIFT;
	public static final int NO_WORLD = 1 << 29;
	public static final int NO_MAP = 1 << 30;

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

	/** Niveau d'opacité propre (0 = utiliser le réglage général). */
	public static int alphaLevel(int value) {
		return (value & ALPHA_BITS) >>> ALPHA_SHIFT;
	}

	public static int withAlphaPercent(int value, int percent) {
		if (percent <= 0) {
			return value & ~ALPHA_BITS;
		}

		int level = Math.max(1, Math.min(15, Math.round(percent * 15 / 100f)));
		return (value & ~ALPHA_BITS) | (level << ALPHA_SHIFT);
	}

	/** Opacité effective (0..1) : propre à la zone, sinon celle donnée par défaut. */
	public static float opacity(int value, float fallback) {
		int level = alphaLevel(value);
		return level == 0 ? fallback : level / 15f;
	}

	public static boolean hiddenInWorld(int value) {
		return (value & NO_WORLD) != 0;
	}

	public static boolean hiddenOnMap(int value) {
		return (value & NO_MAP) != 0;
	}

	/** Clé de regroupement pour le rendu (tout sauf la visibilité). */
	public static int renderKey(int value) {
		return value & ~(NO_WORLD | NO_MAP);
	}
}
