package fr.thivant.mapdraw.server;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerJson;

/**
 * Stockage serveur des calques partagés : un fichier JSON par dimension dans
 * {@code <monde>/mapdraw/<dimension>.json}.
 */
public final class ServerStore {
	private final Path dir;
	private final Map<UUID, LayerData> layers = new LinkedHashMap<>();
	private final Set<String> dirtyDimensions = new HashSet<>();

	public ServerStore(Path worldRoot) {
		this.dir = worldRoot.resolve("mapdraw");
	}

	public void load() {
		layers.clear();

		if (!Files.isDirectory(dir)) {
			return;
		}

		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : files) {
				try {
					for (LayerData layer : LayerJson.readLayers(file)) {
						layer.setMeta(layer.meta().withShared(true));
						layers.put(layer.meta().id(), layer);
					}
				} catch (IOException e) {
					MapDraw.LOGGER.error("Impossible de lire {}", file, e);
				}
			}
		} catch (IOException e) {
			MapDraw.LOGGER.error("Impossible de lister {}", dir, e);
		}

		MapDraw.LOGGER.info("MapDraw : {} calque(s) partagé(s) chargé(s)", layers.size());
	}

	public Collection<LayerData> all() {
		return layers.values();
	}

	public LayerData get(UUID id) {
		return layers.get(id);
	}

	public void put(LayerData layer) {
		layers.put(layer.meta().id(), layer);
		markDirty(layer.meta().dimension());
	}

	public LayerData remove(UUID id) {
		LayerData removed = layers.remove(id);

		if (removed != null) {
			markDirty(removed.meta().dimension());
		}

		return removed;
	}

	public void markDirty(String dimension) {
		dirtyDimensions.add(dimension);
	}

	public boolean isDirty() {
		return !dirtyDimensions.isEmpty();
	}

	/** Réécrit uniquement les fichiers des dimensions modifiées. */
	public void saveDirty() {
		if (dirtyDimensions.isEmpty()) {
			return;
		}

		for (String dimension : new ArrayList<>(dirtyDimensions)) {
			List<LayerData> inDim = new ArrayList<>();

			for (LayerData layer : layers.values()) {
				if (layer.meta().dimension().equals(dimension)) {
					inDim.add(layer);
				}
			}

			Path file = dir.resolve(LayerJson.safeFileName(dimension) + ".json");

			try {
				if (inDim.isEmpty()) {
					Files.deleteIfExists(file);
				} else {
					LayerJson.writeLayers(file, inDim);
				}

				dirtyDimensions.remove(dimension);
			} catch (IOException e) {
				MapDraw.LOGGER.error("Impossible d'écrire {}", file, e);
			}
		}
	}
}
