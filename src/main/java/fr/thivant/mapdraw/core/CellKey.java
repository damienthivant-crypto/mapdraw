package fr.thivant.mapdraw.core;

/**
 * Encodage d'une colonne de blocs (X, Z) dans un long.
 * Les 32 bits de poids fort contiennent X, les 32 bits de poids faible contiennent Z.
 */
public final class CellKey {
	private CellKey() {
	}

	public static long pack(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static int x(long key) {
		return (int) (key >> 32);
	}

	public static int z(long key) {
		return (int) key;
	}

	/** Clé du chunk (16x16) qui contient la colonne. */
	public static long chunkOf(long cellKey) {
		return pack(x(cellKey) >> 4, z(cellKey) >> 4);
	}

	public static long chunkKey(int chunkX, int chunkZ) {
		return pack(chunkX, chunkZ);
	}
}
