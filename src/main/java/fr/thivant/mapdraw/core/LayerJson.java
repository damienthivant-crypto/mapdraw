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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lecture / écriture des calques au format JSON.
 * <pre>
 * {
 *   "format": "mapdraw",
 *   "version": 1,
 *   "layers": [
 *     { "id": "...", "name": "Ma ville", "owner": "...", "ownerName": "Steve",
 *       "dimension": "minecraft:overworld", "shared": true,
 *       "cells": [x, z, 0xRRGGBB, x, z, 0xRRGGBB, ...] }
 *   ]
 * }
 * </pre>
 */
public final class LayerJson {
	public static final String FORMAT = "mapdraw";
	public static final String LAYER_FORMAT = "mapdraw-layer";
	public static final int VERSION = 1;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private LayerJson() {
	}

	public static JsonObject layerToJson(LayerData layer) {
		LayerMeta m = layer.meta();
		JsonObject o = new JsonObject();
		o.addProperty("id", m.id().toString());
		o.addProperty("name", m.name());
		o.addProperty("owner", m.owner().toString());
		o.addProperty("ownerName", m.ownerName());
		o.addProperty("dimension", m.dimension());
		o.addProperty("shared", m.shared());
		JsonArray cells = new JsonArray(layer.size() * 3);
		layer.forEach((cell, color) -> {
			cells.add(CellKey.x(cell));
			cells.add(CellKey.z(cell));
			cells.add(color);
		});
		o.add("cells", cells);
		return o;
	}

	public static LayerData layerFromJson(JsonObject o) {
		UUID id = parseUuid(o, "id", UUID.randomUUID());
		String name = o.has("name") ? o.get("name").getAsString() : "Calque";
		UUID owner = parseUuid(o, "owner", new UUID(0L, 0L));
		String ownerName = o.has("ownerName") ? o.get("ownerName").getAsString() : "?";
		String dimension = o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld";
		boolean shared = o.has("shared") && o.get("shared").getAsBoolean();
		LayerData layer = new LayerData(new LayerMeta(id, name, owner, ownerName, dimension, shared));

		if (o.has("cells") && o.get("cells").isJsonArray()) {
			JsonArray cells = o.getAsJsonArray("cells");

			for (int i = 0; i + 2 < cells.size(); i += 3) {
				int x = cells.get(i).getAsInt();
				int z = cells.get(i + 1).getAsInt();
				int color = cells.get(i + 2).getAsInt() & CellValue.MASK;
				layer.set(CellKey.pack(x, z), color);
			}
		}

		return layer;
	}

	private static UUID parseUuid(JsonObject o, String key, UUID fallback) {
		try {
			return o.has(key) ? UUID.fromString(o.get(key).getAsString()) : fallback;
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	public static void writeLayers(Path file, Collection<LayerData> layers) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("version", VERSION);
		JsonArray arr = new JsonArray();

		for (LayerData layer : layers) {
			arr.add(layerToJson(layer));
		}

		root.add("layers", arr);
		writeAtomic(file, root);
	}

	public static List<LayerData> readLayers(Path file) throws IOException {
		List<LayerData> out = new ArrayList<>();

		if (!Files.isRegularFile(file)) {
			return out;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);

			if (root.isJsonObject() && root.getAsJsonObject().has("layers")) {
				for (JsonElement e : root.getAsJsonObject().getAsJsonArray("layers")) {
					if (e.isJsonObject()) {
						out.add(layerFromJson(e.getAsJsonObject()));
					}
				}
			}
		} catch (RuntimeException e) {
			throw new IOException("Fichier JSON invalide : " + file, e);
		}

		return out;
	}

	/** Export d'un seul calque (secours hors serveur). */
	public static void writeSingleLayer(Path file, LayerData layer) throws IOException {
		JsonObject root = layerToJson(layer);
		root.addProperty("format", LAYER_FORMAT);
		root.addProperty("version", VERSION);
		writeAtomic(file, root);
	}

	public static LayerData readSingleLayer(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);

			if (!root.isJsonObject()) {
				throw new IOException("Ce fichier n'est pas un calque MapDraw");
			}

			JsonObject o = root.getAsJsonObject();

			// On accepte aussi un fichier multi-calques : on prend le premier.
			if (o.has("layers") && o.get("layers").isJsonArray() && !o.getAsJsonArray("layers").isEmpty()) {
				o = o.getAsJsonArray("layers").get(0).getAsJsonObject();
			}

			return layerFromJson(o);
		} catch (RuntimeException e) {
			throw new IOException("Fichier JSON invalide : " + file, e);
		}
	}

	private static void writeAtomic(Path file, JsonElement root) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}

		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
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
