package fr.thivant.mapdraw.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

	// Zones (formes vectorielles) et leurs blocs calculés, uniquement côté client (rasterize = true).
	private final LinkedHashMap<UUID, Zone> zones = new LinkedHashMap<>();
	private boolean rasterize;
	private final Map<UUID, Map<Long, LongIntMap>> zoneRaster = new HashMap<>();
	private final Map<Long, LongIntMap> zoneMerged = new HashMap<>();

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

	/** Efface les traits (les zones sont gérées à part). */
	public void clear() {
		chunks.clear();
		size = 0;
	}

	/** Chunks contenant des traits ou des zones. */
	public Set<Long> chunkKeys() {
		if (zoneMerged.isEmpty()) {
			return Collections.unmodifiableSet(chunks.keySet());
		}

		Set<Long> all = new HashSet<>(chunks.keySet());
		all.addAll(zoneMerged.keySet());
		return all;
	}

	public boolean hasChunk(long chunkKey) {
		return chunks.containsKey(chunkKey) || zoneMerged.containsKey(chunkKey);
	}

	/** Copie des cases d'un chunk (traits puis zones par-dessus, au plus 256). */
	public Map<Long, Integer> cellsInChunk(long chunkKey) {
		if (!hasChunk(chunkKey)) {
			return Collections.emptyMap();
		}

		Map<Long, Integer> out = new HashMap<>();
		forEachInChunk(chunkKey, out::put);
		return out;
	}

	/** Parcourt les cases d'un chunk sans copie (traits, puis blocs des zones qui passent au-dessus). */
	public void forEachInChunk(long chunkKey, LongIntMap.Visitor visitor) {
		LongIntMap chunk = chunks.get(chunkKey);

		if (chunk != null) {
			chunk.forEach(visitor);
		}

		LongIntMap z = zoneMerged.get(chunkKey);

		if (z != null) {
			z.forEach(visitor);
		}
	}

	// ------------------------------------------------------------------ zones

	public Collection<Zone> zones() {
		return Collections.unmodifiableCollection(zones.values());
	}

	public Zone zone(UUID id) {
		return zones.get(id);
	}

	public int zoneCount() {
		return zones.size();
	}

	/** Active le calcul des blocs des zones (client). */
	public void enableZoneRaster() {
		if (!rasterize) {
			rasterize = true;

			for (Zone z : zones.values()) {
				zoneRaster.put(z.id(), rasterOf(z));
			}

			rebuildMerged(allZoneChunks());
		}
	}

	/**
	 * Ajoute ou remplace une zone.
	 *
	 * @return les chunks dont l'affichage change
	 */
	public Set<Long> putZone(Zone zone) {
		Set<Long> touched = new HashSet<>();
		Map<Long, LongIntMap> old = zoneRaster.remove(zone.id());

		if (old != null) {
			touched.addAll(old.keySet());
		}

		zones.put(zone.id(), zone);

		if (rasterize) {
			Map<Long, LongIntMap> raster = rasterOf(zone);
			zoneRaster.put(zone.id(), raster);
			touched.addAll(raster.keySet());
			rebuildMerged(touched);
		}

		return touched;
	}

	public Set<Long> removeZone(UUID id) {
		Set<Long> touched = new HashSet<>();

		if (zones.remove(id) == null) {
			return touched;
		}

		Map<Long, LongIntMap> old = zoneRaster.remove(id);

		if (old != null) {
			touched.addAll(old.keySet());
			rebuildMerged(touched);
		}

		return touched;
	}

	public void clearZones() {
		zones.clear();
		zoneRaster.clear();
		zoneMerged.clear();
	}

	private Set<Long> allZoneChunks() {
		Set<Long> all = new HashSet<>();

		for (Map<Long, LongIntMap> r : zoneRaster.values()) {
			all.addAll(r.keySet());
		}

		return all;
	}

	private static Map<Long, LongIntMap> rasterOf(Zone zone) {
		Map<Long, LongIntMap> byChunk = new HashMap<>();

		try {
			Shapes.Shape shape = zone.shape();
			int inner = zone.interiorValue();
			int outer = zone.outlineValue();

			for (long k : shape.interior()) {
				byChunk.computeIfAbsent(CellKey.chunkOf(k), c -> new LongIntMap(64)).put(k, inner);
			}

			for (long k : shape.outline()) {
				byChunk.computeIfAbsent(CellKey.chunkOf(k), c -> new LongIntMap(64)).put(k, outer);
			}
		} catch (Shapes.TooLargeException e) {
			// zone trop grande : rien à afficher
		}

		return byChunk;
	}

	/** Recalcule la fusion des zones (dans l'ordre de création) pour les chunks donnés. */
	private void rebuildMerged(Collection<Long> chunkKeys) {
		for (long chunk : chunkKeys) {
			LongIntMap merged = null;

			for (Zone z : zones.values()) {
				Map<Long, LongIntMap> raster = zoneRaster.get(z.id());
				LongIntMap part = raster == null ? null : raster.get(chunk);

				if (part != null) {
					if (merged == null) {
						merged = new LongIntMap(part.size() * 2);
					}

					part.forEach(merged::put);
				}
			}

			if (merged == null) {
				zoneMerged.remove(chunk);
			} else {
				zoneMerged.put(chunk, merged);
			}
		}
	}

	/** Zone la plus haute contenant le bloc (x, z) — les zones visibles sur la carte d'abord — ou null. */
	public Zone zoneAt(int x, int z) {
		List<Zone> list = new ArrayList<>(zones.values());
		Zone hidden = null;

		for (int i = list.size() - 1; i >= 0; i--) {
			Zone zone = list.get(i);

			if (zone.contains(x, z)) {
				if (zone.showMap()) {
					return zone;
				}

				if (hidden == null) {
					hidden = zone;
				}
			}
		}

		return hidden;
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
