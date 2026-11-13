package fr.thivant.mapdraw.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerMeta;
import fr.thivant.mapdraw.net.MapDrawPayloads;
import fr.thivant.mapdraw.net.MapDrawPayloads.CellsC2S;
import fr.thivant.mapdraw.net.MapDrawPayloads.CellsS2C;
import fr.thivant.mapdraw.net.MapDrawPayloads.HelloS2C;
import fr.thivant.mapdraw.net.MapDrawPayloads.LayerDeleteC2S;
import fr.thivant.mapdraw.net.MapDrawPayloads.LayerDeleteS2C;
import fr.thivant.mapdraw.net.MapDrawPayloads.LayerMetaC2S;
import fr.thivant.mapdraw.net.MapDrawPayloads.LayerMetaS2C;
import fr.thivant.mapdraw.net.MapDrawPayloads.MessageS2C;

/**
 * Logique serveur : stockage des calques partagés, contrôle des droits, diffusion groupée.
 * <p>
 * Les modifications reçues pendant un tick sont regroupées par calque et par auteur, puis diffusées
 * en fin de tick (toutes les 50 ms) à tous les autres joueurs qui ont le mod.
 */
public final class ServerSync {
	private static final int SAVE_INTERVAL_TICKS = 200;
	private static final int MAX_LAYERS_PER_PLAYER = 64;
	private static final int MAX_CELLS_PER_LAYER = 262_144;
	private static final UUID NOBODY = new UUID(0L, 0L);

	private static MinecraftServer server;
	private static ServerStore store;
	private static int tickCounter;

	/** calque -> auteur -> (case -> couleur ou -1) en attente de diffusion. */
	private static final Map<UUID, Map<UUID, LinkedHashMap<Long, Integer>>> pending = new LinkedHashMap<>();
	private static final Map<UUID, Long> lastErrorTime = new HashMap<>();

	private ServerSync() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			store = new ServerStore(s.getWorldPath(LevelResource.ROOT));
			store.load();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			flush();

			if (store != null) {
				store.saveDirty();
			}

			pending.clear();
			lastErrorTime.clear();
			store = null;
			server = null;
		});

		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (store == null) {
				return;
			}

			flush();

			if (++tickCounter >= SAVE_INTERVAL_TICKS) {
				tickCounter = 0;
				store.saveDirty();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> sendFullState(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> lastErrorTime.remove(handler.player.getUUID()));

		ServerPlayNetworking.registerGlobalReceiver(LayerMetaC2S.TYPE, (payload, context) -> onLayerMeta(context.player(), payload.meta()));
		ServerPlayNetworking.registerGlobalReceiver(LayerDeleteC2S.TYPE, (payload, context) -> onLayerDelete(context.player(), payload.layer()));
		ServerPlayNetworking.registerGlobalReceiver(CellsC2S.TYPE, (payload, context) -> onCells(context.player(), payload));
	}

	// ------------------------------------------------------------------ droits

	public static boolean isOperator(ServerPlayer player) {
		try {
			return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean canEdit(ServerPlayer player, LayerData layer) {
		return layer.meta().owner().equals(player.getUUID()) || isOperator(player);
	}

	// ------------------------------------------------------------------ réception

	private static void onLayerMeta(ServerPlayer player, LayerMeta meta) {
		if (store == null) {
			return;
		}

		LayerData existing = store.get(meta.id());

		if (existing == null) {
			if (!meta.shared()) {
				return;
			}

			long owned = store.all().stream().filter(l -> l.meta().owner().equals(player.getUUID())).count();

			if (owned >= MAX_LAYERS_PER_PLAYER) {
				error(player, "Limite de " + MAX_LAYERS_PER_PLAYER + " calques partagés atteinte.");
				ServerPlayNetworking.send(player, new LayerDeleteS2C(meta.id()));
				return;
			}

			// Le propriétaire est toujours l'expéditeur : on ne fait pas confiance au client.
			LayerMeta created = new LayerMeta(meta.id(), meta.name(), player.getUUID(), player.getName().getString(), meta.dimension(), true);
			LayerData layer = new LayerData(created);
			store.put(layer);
			broadcast(new LayerMetaS2C(created), player.getUUID());
			return;
		}

		if (!canEdit(player, existing)) {
			error(player, "Le calque « " + existing.meta().name() + " » appartient à " + existing.meta().ownerName() + ".");
			ServerPlayNetworking.send(player, new LayerMetaS2C(existing.meta()));
			return;
		}

		LayerMeta updated = existing.meta().withName(meta.name());
		existing.setMeta(updated);
		store.markDirty(updated.dimension());
		broadcast(new LayerMetaS2C(updated), NOBODY);
	}

	private static void onLayerDelete(ServerPlayer player, UUID id) {
		if (store == null) {
			return;
		}

		LayerData existing = store.get(id);

		if (existing == null) {
			return;
		}

		if (!canEdit(player, existing)) {
			error(player, "Seul " + existing.meta().ownerName() + " (ou un OP) peut supprimer « " + existing.meta().name() + " ».");
			resync(player, existing);
			return;
		}

		store.remove(id);
		pending.remove(id);
		broadcast(new LayerDeleteS2C(id), player.getUUID());
	}

	private static void onCells(ServerPlayer player, CellsC2S payload) {
		if (store == null) {
			return;
		}

		LayerData layer = store.get(payload.layer());

		if (layer == null) {
			return;
		}

		if (!canEdit(player, layer)) {
			error(player, "Vous ne pouvez pas modifier « " + layer.meta().name() + " » (propriétaire : " + layer.meta().ownerName() + ").");
			resync(player, layer);
			return;
		}

		long[] keys = payload.keys();
		int[] colors = payload.colors();
		int count = Math.min(keys.length, colors.length);
		LinkedHashMap<Long, Integer> out = pending
				.computeIfAbsent(layer.meta().id(), k -> new LinkedHashMap<>())
				.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
		boolean refused = false;

		for (int i = 0; i < count; i++) {
			int color = colors[i] == LayerData.ERASE ? LayerData.ERASE : (colors[i] & 0xFFFFFF);

			if (color != LayerData.ERASE && layer.size() >= MAX_CELLS_PER_LAYER && layer.get(keys[i]) == null) {
				refused = true;
				continue;
			}

			layer.set(keys[i], color);
			out.put(keys[i], color);
		}

		store.markDirty(layer.meta().dimension());

		if (refused) {
			error(player, "Calque plein (" + MAX_CELLS_PER_LAYER + " blocs max).");
		}
	}

	// ------------------------------------------------------------------ envoi

	private static void flush() {
		if (server == null || pending.isEmpty()) {
			pending.clear();
			return;
		}

		for (Map.Entry<UUID, Map<UUID, LinkedHashMap<Long, Integer>>> byLayer : pending.entrySet()) {
			UUID layerId = byLayer.getKey();

			for (Map.Entry<UUID, LinkedHashMap<Long, Integer>> bySender : byLayer.getValue().entrySet()) {
				for (CellsS2C packet : split(layerId, bySender.getValue())) {
					broadcast(packet, bySender.getKey());
				}
			}
		}

		pending.clear();
	}

	private static List<CellsS2C> split(UUID layer, Map<Long, Integer> cells) {
		List<CellsS2C> packets = new ArrayList<>();
		int n = cells.size();

		if (n == 0) {
			return packets;
		}

		long[] keys = new long[Math.min(n, MapDrawPayloads.MAX_CELLS_S2C)];
		int[] colors = new int[keys.length];
		int i = 0;
		int remaining = n;

		for (Map.Entry<Long, Integer> e : cells.entrySet()) {
			keys[i] = e.getKey();
			colors[i] = e.getValue();
			i++;
			remaining--;

			if (i == keys.length) {
				packets.add(new CellsS2C(layer, keys, colors));
				int next = Math.min(remaining, MapDrawPayloads.MAX_CELLS_S2C);

				if (next == 0) {
					break;
				}

				keys = new long[next];
				colors = new int[next];
				i = 0;
			}
		}

		return packets;
	}

	private static void broadcast(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload, UUID except) {
		if (server == null) {
			return;
		}

		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.getUUID().equals(except) && ServerPlayNetworking.canSend(p, payload.type())) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private static void sendLayer(ServerPlayer player, LayerData layer) {
		ServerPlayNetworking.send(player, new LayerMetaS2C(layer.meta()));

		for (CellsS2C packet : split(layer.meta().id(), layer.snapshot())) {
			ServerPlayNetworking.send(player, packet);
		}
	}

	/** Annule côté client une modification refusée : on renvoie l'état exact du calque. */
	private static void resync(ServerPlayer player, LayerData layer) {
		ServerPlayNetworking.send(player, new LayerDeleteS2C(layer.meta().id()));
		sendLayer(player, layer);
	}

	private static void sendFullState(ServerPlayer player) {
		if (store == null || !ServerPlayNetworking.canSend(player, HelloS2C.TYPE)) {
			return;
		}

		ServerPlayNetworking.send(player, new HelloS2C(MapDrawPayloads.PROTOCOL, isOperator(player)));

		for (LayerData layer : store.all()) {
			sendLayer(player, layer);
		}
	}

	private static void error(ServerPlayer player, String message) {
		long now = System.currentTimeMillis();
		Long last = lastErrorTime.get(player.getUUID());

		if (last != null && now - last < 1000) {
			return;
		}

		lastErrorTime.put(player.getUUID(), now);

		if (ServerPlayNetworking.canSend(player, MessageS2C.TYPE)) {
			ServerPlayNetworking.send(player, new MessageS2C(message));
		}

		MapDraw.LOGGER.debug("Refus pour {} : {}", player.getName().getString(), message);
	}
}
