package fr.thivant.mapdraw.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerJson;

/**
 * Sauvegarde client :
 * <ul>
 *   <li>calques privés : {@code config/mapdraw/worlds/<monde ou serveur>/<dimension>.json}</li>
 *   <li>affichage (calques masqués, calque actif) : {@code .../view.json}</li>
 *   <li>exports : {@code config/mapdraw/exports/*.json}</li>
 * </ul>
 */
public final class ClientStorage {
	private static Path worldDir;

	private ClientStorage() {
	}

	/** Identifiant stable du monde solo ou du serveur courant. */
	public static String currentWorldId(Minecraft mc) {
		MinecraftServer sp = mc.getSingleplayerServer();

		if (sp != null) {
			return "solo_" + sp.getWorldData().getLevelName();
		}

		ServerData server = mc.getCurrentServer();

		if (server != null) {
			return server.isRealm() ? "realms_" + server.name : "serveur_" + server.ip;
		}

		return "inconnu";
	}

	public static void open(Minecraft mc, LayerManager layers) {
		worldDir = ClientConfig.dataDir().resolve("worlds").resolve(LayerJson.safeFileName(currentWorldId(mc)));

		if (Files.isDirectory(worldDir)) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(worldDir, "*.json")) {
				for (Path file : files) {
					if (file.getFileName().toString().equals("view.json")) {
						continue;
					}

					try {
						for (LayerData layer : LayerJson.readLayers(file)) {
							layers.loadPrivate(layer);
						}
					} catch (IOException e) {
						MapDraw.LOGGER.error("Calques illisibles : {}", file, e);
					}
				}
			} catch (IOException e) {
				MapDraw.LOGGER.error("Impossible de lister {}", worldDir, e);
			}
		}

		loadView(layers);
	}

	public static void close(LayerManager layers) {
		if (worldDir != null) {
			savePrivate(layers);
			saveView(layers);
		}

		worldDir = null;
	}

	/** Réécrit les fichiers de calques privés (un par dimension). */
	public static void savePrivate(LayerManager layers) {
		if (worldDir == null) {
			return;
		}

		Map<String, List<LayerData>> byDim = new HashMap<>();

		for (LayerData layer : layers.privateLayers()) {
			byDim.computeIfAbsent(layer.meta().dimension(), k -> new ArrayList<>()).add(layer);
		}

		try {
			Files.createDirectories(worldDir);
			Set<String> written = new HashSet<>();

			for (Map.Entry<String, List<LayerData>> e : byDim.entrySet()) {
				String fileName = LayerJson.safeFileName(e.getKey()) + ".json";
				LayerJson.writeLayers(worldDir.resolve(fileName), e.getValue());
				written.add(fileName);
			}

			// Supprime les fichiers des dimensions qui n'ont plus de calque privé.
			try (DirectoryStream<Path> files = Files.newDirectoryStream(worldDir, "*.json")) {
				for (Path file : files) {
					String name = file.getFileName().toString();

					if (!name.equals("view.json") && !written.contains(name)) {
						Files.deleteIfExists(file);
					}
				}
			}
		} catch (IOException e) {
			MapDraw.LOGGER.error("Impossible d'enregistrer les calques privés", e);
		}
	}

	public static void saveView(LayerManager layers) {
		if (worldDir == null) {
			return;
		}

		JsonObject root = new JsonObject();
		JsonArray hidden = new JsonArray();
		layers.hiddenIds().forEach(id -> hidden.add(id.toString()));
		root.add("hidden", hidden);
		JsonArray worldHidden = new JsonArray();
		layers.worldHiddenIds().forEach(id -> worldHidden.add(id.toString()));
		root.add("worldHidden", worldHidden);

		if (layers.activeId() != null) {
			root.addProperty("active", layers.activeId().toString());
		}

		try {
			Files.createDirectories(worldDir);

			try (Writer w = Files.newBufferedWriter(worldDir.resolve("view.json"), StandardCharsets.UTF_8)) {
				w.write(root.toString());
			}
		} catch (IOException e) {
			MapDraw.LOGGER.warn("Impossible d'enregistrer view.json", e);
		}
	}

	private static void loadView(LayerManager layers) {
		Path file = worldDir.resolve("view.json");

		if (!Files.isRegularFile(file)) {
			return;
		}

		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			List<UUID> hidden = new ArrayList<>();

			if (root.has("hidden")) {
				for (JsonElement e : root.getAsJsonArray("hidden")) {
					hidden.add(UUID.fromString(e.getAsString()));
				}
			}

			List<UUID> worldHidden = new ArrayList<>();

			if (root.has("worldHidden")) {
				for (JsonElement e : root.getAsJsonArray("worldHidden")) {
					worldHidden.add(UUID.fromString(e.getAsString()));
				}
			}

			UUID active = root.has("active") ? UUID.fromString(root.get("active").getAsString()) : null;
			layers.restoreView(hidden, worldHidden, active);
		} catch (Exception e) {
			MapDraw.LOGGER.warn("view.json illisible", e);
		}
	}

	// ------------------------------------------------------------------ export / import

	public static Path exportDir() {
		return ClientConfig.dataDir().resolve("exports");
	}

	public static Path exportLayer(LayerData layer) throws IOException {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path file = exportDir().resolve(LayerJson.safeFileName(layer.meta().name()) + "_" + stamp + ".json");
		LayerJson.writeSingleLayer(file, layer);
		return file;
	}

	/** Fichiers importables, du plus récent au plus ancien. */
	public static List<Path> listImportable() {
		List<Path> out = new ArrayList<>();

		try {
			Files.createDirectories(exportDir());

			try (DirectoryStream<Path> files = Files.newDirectoryStream(exportDir(), "*.json")) {
				files.forEach(out::add);
			}
		} catch (IOException e) {
			MapDraw.LOGGER.warn("Dossier d'export illisible", e);
		}

		out.sort(Comparator.comparingLong(ClientStorage::modified).reversed());
		return out;
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}
}
