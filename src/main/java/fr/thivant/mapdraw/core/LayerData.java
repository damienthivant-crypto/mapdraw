package fr.thivant.mapdraw.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Un calque : métadonnées + cases, indexées par chunk pour reconstruire vite les meshes.
 * Valeur d'une case : voir {@link CellValue} (couleur RGB + drapeau surface). Une case absente = pas de tracé.
 * Stockage compact sans objets par case ({@link LongIntMap}).
 */
public final class LayerData {
	/** Valeur réseau signifiant « effacer la case ». */
	public static final int ERASE = -1;

	private LayerMeta meta;
	private final Map<Long, LongIntMap> chunks = new HashMap<>();
	private int size;

	public LayerData(LayerMeta meta) {
		this.meta = meta;
	}

	public LayerMeta meta() {
		return meta;
	}

	public void setMeta(LayerMeta meta) {
		this.meta = meta;
	}

	public int size() {
		return size;
	}

	/** Valeur de la case, ou {@link LongIntMap#NONE}. */
	public int getRaw(long cell) {
		LongIntMap chunk = chunks.get(CellKey.chunkOf(cell));
		return chunk == null ? LongIntMap.NONE : chunk.get(cell);
	}

	public Integer get(long cell) {
		int v = getRaw(cell);
		return v == LongIntMap.NONE ? null : v;
	}

	/**
	 * Place (value >= 0) ou efface (value == ERASE ou NONE) une case.
	 *
	 * @return l'ancienne valeur, ou {@link LongIntMap#NONE} si la case était vide
	 */
	public int setRaw(long cell, int value) {
		long chunkKey = CellKey.chunkOf(cell);

		if (value == ERASE || value == LongIntMap.NONE) {
			LongIntMap chunk = chunks.get(chunkKey);

			if (chunk == null) {
				return LongIntMap.NONE;
			}

			int old = chunk.remove(cell);

			if (old != LongIntMap.NONE) {
				size--;

				if (chunk.isEmpty()) {
					chunks.remove(chunkKey);
				}
			}

			return old;
		}

		LongIntMap chunk = chunks.get(chunkKey);

		if (chunk == null) {
			chunk = new LongIntMap(32);
			chunks.put(chunkKey, chunk);
		}

		int old = chunk.put(cell, value & CellValue.MASK);

		if (old == LongIntMap.NONE) {
			size++;
		}

		return old;
	}

	/** Version objet : null = effacer / case vide. */
	public Integer set(long cell, Integer value) {
		int old = setRaw(cell, value == null ? ERASE : value);
		return old == LongIntMap.NONE ? null : old;
	}

	public void clear() {
		chunks.clear();
		size = 0;
	}

	public Set<Long> chunkKeys() {
		return Collections.unmodifiableSet(chunks.keySet());
	}

	/** Copie des cases d'un chunk (au plus 256). */
	public Map<Long, Integer> cellsInChunk(long chunkKey) {
		LongIntMap chunk = chunks.get(chunkKey);

		if (chunk == null) {
			return Collections.emptyMap();
		}

		Map<Long, Integer> out = new HashMap<>(chunk.size() * 2);
		chunk.forEach(out::put);
		return out;
	}

	/** Parcourt les cases d'un chunk sans copie. */
	public void forEachInChunk(long chunkKey, LongIntMap.Visitor visitor) {
		LongIntMap chunk = chunks.get(chunkKey);

		if (chunk != null) {
			chunk.forEach(visitor);
		}
	}

	public void forEach(LongIntMap.Visitor visitor) {
		for (LongIntMap chunk : chunks.values()) {
			chunk.forEach(visitor);
		}
	}

	/** Copie de toutes les cases (clé -> valeur). À éviter sur les gros calques. */
	public Map<Long, Integer> snapshot() {
		Map<Long, Integer> copy = new HashMap<>(Math.max(16, size * 2));
		forEach(copy::put);
		return copy;
	}

	/** Copie compacte : clés et valeurs dans deux tableaux. */
	public Cells toArrays() {
		long[] keys = new long[size];
		int[] values = new int[size];
		int[] n = {0};
		forEach((k, v) -> {
			keys[n[0]] = k;
			values[n[0]] = v;
			n[0]++;
		});
		return new Cells(keys, values);
	}

	/** Paire de tableaux clés / valeurs. */
	public record Cells(long[] keys, int[] values) {
	}
}
