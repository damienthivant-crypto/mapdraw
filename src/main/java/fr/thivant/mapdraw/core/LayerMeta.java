package fr.thivant.mapdraw.core;

import java.util.UUID;

/**
 * Métadonnées d'un calque (sans les cases).
 *
 * @param id        identifiant unique
 * @param name      nom affiché
 * @param owner     UUID du joueur propriétaire
 * @param ownerName pseudo du propriétaire
 * @param dimension identifiant de dimension, par ex. "minecraft:overworld"
 * @param shared    true si le calque est partagé via le serveur
 */
public record LayerMeta(UUID id, String name, UUID owner, String ownerName, String dimension, boolean shared) {
	public static final int MAX_NAME = 48;

	public LayerMeta {
		name = sanitizeName(name);
		ownerName = ownerName == null ? "?" : ownerName;
		dimension = dimension == null ? "minecraft:overworld" : dimension;
	}

	public static String sanitizeName(String name) {
		if (name == null) {
			return "Calque";
		}

		String trimmed = name.strip();

		if (trimmed.isEmpty()) {
			return "Calque";
		}

		return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
	}

	public LayerMeta withName(String newName) {
		return new LayerMeta(id, newName, owner, ownerName, dimension, shared);
	}

	public LayerMeta withShared(boolean newShared) {
		return new LayerMeta(id, name, owner, ownerName, dimension, newShared);
	}
}
