package fr.thivant.mapdraw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

import fr.thivant.mapdraw.net.MapDrawPayloads;
import fr.thivant.mapdraw.server.ServerSync;

/**
 * Point d'entrée commun (client + serveur dédié).
 */
public final class MapDraw implements ModInitializer {
	public static final String MOD_ID = "mapdraw";
	public static final Logger LOGGER = LoggerFactory.getLogger("MapDraw");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		MapDrawPayloads.register();
		ServerSync.init();
		LOGGER.info("MapDraw initialisé (protocole {})", MapDrawPayloads.PROTOCOL);
	}
}
