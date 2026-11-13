package fr.thivant.mapdraw.client;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import fr.thivant.mapdraw.MapDraw;

/**
 * Configuration client, enregistrée dans {@code config/mapdraw.json}.
 */
public final class ClientConfig {
	public enum RenderMode {
		/** Mesh GPU persistant par chunk (rapide, par défaut). */
		GPU,
		/** Rendu via le pipeline vanilla des boîtes de debug (compatibilité maximale, profondeur toujours testée). */
		COMPAT
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance = new ClientConfig();

	// Affichage dans le monde
	public boolean showInWorld = true;
	public float opacity = 0.45f;
	public int maxDistance = 256;
	public boolean seeThrough = false;
	public RenderMode renderMode = RenderMode.GPU;

	// Outils (mémorisés entre les sessions)
	public int color = 0xE53935;
	public int width = 1;

	// Carte JourneyMap
	public float journeyMapOpacity = 0.6f;

	public static ClientConfig get() {
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("mapdraw.json");
	}

	public static Path dataDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("mapdraw");
	}

	public static void load() {
		Path file = file();

		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);

				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				MapDraw.LOGGER.warn("Configuration illisible, valeurs par défaut utilisées", e);
			}
		}

		instance.clamp();
	}

	public void clamp() {
		opacity = Math.max(0.1f, Math.min(1.0f, opacity));
		journeyMapOpacity = Math.max(0.1f, Math.min(1.0f, journeyMapOpacity));
		maxDistance = Math.max(16, Math.min(1024, maxDistance));
		width = Math.max(1, Math.min(5, width));
		color &= 0xFFFFFF;

		if (renderMode == null) {
			renderMode = RenderMode.GPU;
		}
	}

	public void save() {
		clamp();

		try {
			Files.createDirectories(file().getParent());

			try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (Exception e) {
			MapDraw.LOGGER.warn("Impossible d'enregistrer la configuration", e);
		}
	}
}
