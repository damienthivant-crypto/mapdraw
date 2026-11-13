package fr.thivant.mapdraw.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Lecture / écriture des calques au format JSON, en flux (aucun arbre JSON en mémoire : supporte
 * des calques de plusieurs centaines de milliers de blocs).
 * <pre>
 * {
 *   "format": "mapdraw",
 *   "version": 1,
 *   "layers": [
 *     { "id": "...", "name": "Ma ville", "owner": "...", "ownerName": "Steve",
 *       "dimension": "minecraft:overworld", "shared": true,
 *       "cells": [x, z, valeur, x, z, valeur, ...] }
 *   ]
 * }
 * </pre>
 * Valeur = couleur RGB, plus le bit 24 pour une surface (voir {@link CellValue}).
 */
public final class LayerJson {
	public static final String FORMAT = "mapdraw";
	public static final String LAYER_FORMAT = "mapdraw-layer";
	public static final int VERSION = 1;

	private LayerJson() {
	}

	/** Copie figée d'un calque, prête à être écrite sur un autre thread. */
	public record Snapshot(LayerMeta meta, long[] keys, int[] values) {
	}

	public static Snapshot snapshot(LayerData layer) {
		LayerData.Cells cells = layer.toArrays();
		return new Snapshot(layer.meta(), cells.keys(), cells.values());
	}

	public static List<Snapshot> snapshots(Collection<LayerData> layers) {
		List<Snapshot> out = new ArrayList<>(layers.size());

		for (LayerData layer : layers) {
			out.add(snapshot(layer));
		}

		return out;
	}

	// ------------------------------------------------------------------ écriture

	private static void writeLayerBody(JsonWriter w, Snapshot s) throws IOException {
		LayerMeta m = s.meta();
		w.name("id").value(m.id().toString());
		w.name("name").value(m.name());
		w.name("owner").value(m.owner().toString());
		w.name("ownerName").value(m.ownerName());
		w.name("dimension").value(m.dimension());
		w.name("shared").value(m.shared());
		w.name("cells").beginArray();

		for (int i = 0; i < s.keys().length; i++) {
			w.value(CellKey.x(s.keys()[i]));
			w.value(CellKey.z(s.keys()[i]));
			w.value(s.values()[i]);
		}

		w.endArray();
	}

	public static void writeLayers(Path file, Collection<LayerData> layers) throws IOException {
		writeSnapshots(file, snapshots(layers));
	}

	public static void writeSnapshots(Path file, List<Snapshot> layers) throws IOException {
		writeAtomic(file, w -> {
			w.beginObject();
			w.name("format").value(FORMAT);
			w.name("version").value(VERSION);
			w.name("layers").beginArray();

			for (Snapshot s : layers) {
				w.beginObject();
				writeLayerBody(w, s);
				w.endObject();
			}

			w.endArray();
			w.endObject();
		});
	}

	/** Export d'un seul calque (secours hors serveur). */
	public static void writeSingleLayer(Path file, LayerData layer) throws IOException {
		Snapshot s = snapshot(layer);
		writeAtomic(file, w -> {
			w.beginObject();
			w.name("format").value(LAYER_FORMAT);
			w.name("version").value(VERSION);
			writeLayerBody(w, s);
			w.endObject();
		});
	}

	private interface Body {
		void write(JsonWriter w) throws IOException;
	}

	private static void writeAtomic(Path file, Body body) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8); JsonWriter w = new JsonWriter(writer)) {
			body.write(w);
		}

		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// ------------------------------------------------------------------ lecture

	/** Lit un objet calque ; les champs inconnus (format, version…) sont ignorés. */
	private static LayerData readLayerObject(JsonReader r) throws IOException {
		UUID id = UUID.randomUUID();
		String name = "Calque";
		UUID owner = new UUID(0L, 0L);
		String ownerName = "?";
		String dimension = "minecraft:overworld";
		boolean shared = false;
		LongIntMap cells = new LongIntMap();
		LayerData nested = null;
		r.beginObject();

		while (r.hasNext()) {
			String key = r.nextName();

			switch (key) {
				case "id" -> id = parseUuid(r.nextString(), id);
				case "name" -> name = r.nextString();
				case "owner" -> owner = parseUuid(r.nextString(), owner);
				case "ownerName" -> ownerName = r.nextString();
				case "dimension" -> dimension = r.nextString();
				case "shared" -> shared = r.nextBoolean();
				case "cells" -> {
					r.beginArray();

					while (r.hasNext()) {
						int x = r.nextInt();
						int z = r.hasNext() ? r.nextInt() : 0;
						int v = r.hasNext() ? r.nextInt() : 0;
						cells.put(CellKey.pack(x, z), v & CellValue.MASK);
					}

					r.endArray();
				}
				case "layers" -> {
					// fichier multi-calques lu comme un seul : on prend le premier
					r.beginArray();

					while (r.hasNext()) {
						LayerData l = readLayerObject(r);

						if (nested == null) {
							nested = l;
						}
					}

					r.endArray();
				}
				default -> r.skipValue();
			}
		}

		r.endObject();

		if (nested != null) {
			return nested;
		}

		LayerData layer = new LayerData(new LayerMeta(id, name, owner, ownerName, dimension, shared));
		cells.forEach(layer::setRaw);
		return layer;
	}

	private static UUID parseUuid(String s, UUID fallback) {
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	public static List<LayerData> readLayers(Path file) throws IOException {
		List<LayerData> out = new ArrayList<>();

		if (!Files.isRegularFile(file)) {
			return out;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8); JsonReader r = new JsonReader(reader)) {
			r.beginObject();

			while (r.hasNext()) {
				if (r.nextName().equals("layers") && r.peek() == JsonToken.BEGIN_ARRAY) {
					r.beginArray();

					while (r.hasNext()) {
						out.add(readLayerObject(r));
					}

					r.endArray();
				} else {
					r.skipValue();
				}
			}

			r.endObject();
		} catch (RuntimeException e) {
			throw new IOException("Fichier JSON invalide : " + file, e);
		}

		return out;
	}

	public static LayerData readSingleLayer(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8); JsonReader r = new JsonReader(reader)) {
			return readLayerObject(r);
		} catch (RuntimeException e) {
			throw new IOException("Fichier JSON invalide : " + file, e);
		}
	}

	/** Nom de fichier sûr à partir d'un identifiant (dimension, serveur, nom de calque). */
	public static String safeFileName(String raw) {
		StringBuilder sb = new StringBuilder(raw.length());

		for (char c : raw.toCharArray()) {
			if (Character.isLetterOrDigit(c) || c == '-' || c == '.') {
				sb.append(c);
			} else {
				sb.append('_');
			}
		}

		String s = sb.toString();
		return s.isEmpty() ? "_" : (s.length() > 80 ? s.substring(0, 80) : s);
	}
}
