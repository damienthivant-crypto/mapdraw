package fr.thivant.mapdraw.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Un calque : métadonnées + cases colorées, indexées par chunk pour reconstruire vite les meshes.
 * Couleur = RGB sur 24 bits (0xRRGGBB). Une case absente = pas de tracé.
 */
public final class LayerData {
	/** Valeur réseau signifiant « effacer la case ». */
	public static final int ERASE = -1;

	private LayerMeta meta;
	private final Map<Long, Map<Long, Integer>> chunks = new HashMap<>();
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

	public Integer get(long cell) {
		Map<Long, Integer> chunk = chunks.get(CellKey.chunkOf(cell));
		return chunk == null ? null : chunk.get(cell);
	}

	/**
	 * Place (color >= 0) ou efface (color == ERASE ou null) une case.
	 *
	 * @return l'ancienne couleur, ou null si la case était vide
	 */
	public Integer set(long cell, Integer color) {
		long chunkKey = CellKey.chunkOf(cell);

		if (color == null || color == ERASE) {
			Map<Long, Integer> chunk = chunks.get(chunkKey);

			if (chunk == null) {
				return null;
			}

			Integer old = chunk.remove(cell);

			if (old != null) {
				size--;

				if (chunk.isEmpty()) {
					chunks.remove(chunkKey);
				}
			}

			return old;
		}

		int rgb = color & CellValue.MASK;
		Integer old = chunks.computeIfAbsent(chunkKey, k -> new HashMap<>()).put(cell, rgb);

		if (old == null) {
			size++;
		}

		return old;
	}

	public void clear() {
		chunks.clear();
		size = 0;
	}

	public Set<Long> chunkKeys() {
		return Collections.unmodifiableSet(chunks.keySet());
	}

	public Map<Long, Integer> cellsInChunk(long chunkKey) {
		Map<Long, Integer> chunk = chunks.get(chunkKey);
		return chunk == null ? Collections.emptyMap() : Collections.unmodifiableMap(chunk);
	}

	public interface CellVisitor {
		void visit(long cell, int color);
	}

	public void forEach(CellVisitor visitor) {
		for (Map<Long, Integer> chunk : chunks.values()) {
			for (Map.Entry<Long, Integer> e : chunk.entrySet()) {
				visitor.visit(e.getKey(), e.getValue());
			}
		}
	}

	/** Copie de toutes les cases (clé -> couleur). */
	public Map<Long, Integer> snapshot() {
		Map<Long, Integer> copy = new HashMap<>(Math.max(16, size * 2));
		forEach(copy::put);
		return copy;
	}
}
