package fr.thivant.mapdraw.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.core.CellValue;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerMeta;
import fr.thivant.mapdraw.core.LongIntMap;
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
 * Logique serveur (Fabric) : stockage des calques partagés, contrôle des droits, diffusion groupée.
 * <p>
 * Les modifications reçues pendant un tick sont regroupées par calque et par auteur, transformées en
 * paquets en fin de tick, puis envoyées aux autres joueurs à raison d'un nombre limité de paquets par tick.
 */
public final class ServerSync {
	private static final int SAVE_INTERVAL_TICKS = 200;
	private static final int MAX_LAYERS_PER_PLAYER = 64;
	public static final int MAX_CELLS_PER_LAYER = 2_000_000;
	private static final int MAX_BROADCASTS_PER_TICK = 6;
	private static final UUID NOBODY = new UUID(0L, 0L);

	private static MinecraftServer server;
	private static ServerStore store;
	private static int tickCounter;

	/** calque -> auteur -> (case -> valeur ou -1) reçus pendant le tick. */
	private static final Map<UUID, Map<UUID, LongIntMap>> pending = new LinkedHashMap<>();
	/** Paquets prêts, envoyés progressivement. */
	private static final Deque<Broadcast> queue = new ArrayDeque<>();
	private static final Map<UUID, Long> lastErrorTime = new HashMap<>();

	private record Broadcast(CellsS2C packet, UUID except) {
	}

	private ServerSync() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			store = new ServerStore(s.getWorldPath(LevelResource.ROOT));
			store.load();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			collect();

			while (!queue.isEmpty()) {
				sendQueued(Integer.MAX_VALUE);
			}

			if (store != null) {
				store.saveDirty(false);
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

			collect();
			sendQueued(MAX_BROADCASTS_PER_TICK);

			if (++tickCounter >= SAVE_INTERVAL_TICKS) {
				tickCounter = 0;
				store.saveDirty(true);
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
			store.put(new LayerData(created));
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
		queue.removeIf(b -> b.packet().layer().equals(id));
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
		LongIntMap out = pending
				.computeIfAbsent(layer.meta().id(), k -> new LinkedHashMap<>())
				.computeIfAbsent(player.getUUID(), k -> new LongIntMap(count));
		boolean refused = false;

		for (int i = 0; i < count; i++) {
			int value = colors[i] == LayerData.ERASE ? LayerData.ERASE : (colors[i] & CellValue.MASK);

			if (value != LayerData.ERASE && layer.size() >= MAX_CELLS_PER_LAYER && layer.getRaw(keys[i]) == LongIntMap.NONE) {
				refused = true;
				continue;
			}

			layer.setRaw(keys[i], value);
			out.put(keys[i], value);
		}

		store.markDirty(layer.meta().dimension());

		if (refused) {
			error(player, "Calque plein (" + MAX_CELLS_PER_LAYER + " blocs max).");
		}
	}

	// ------------------------------------------------------------------ envoi

	/** Transforme les modifications du tick en paquets mis en file. */
	private static void collect() {
		for (Map.Entry<UUID, Map<UUID, LongIntMap>> byLayer : pending.entrySet()) {
			for (Map.Entry<UUID, LongIntMap> bySender : byLayer.getValue().entrySet()) {
				for (CellsS2C packet : split(byLayer.getKey(), bySender.getValue())) {
					queue.add(new Broadcast(packet, bySender.getKey()));
				}
			}
		}

		pending.clear();
	}

	private static void sendQueued(int max) {
		for (int i = 0; i < max && !queue.isEmpty(); i++) {
			Broadcast b = queue.poll();
			broadcast(b.packet(), b.except());
		}
	}

	private static List<CellsS2C> split(UUID layer, LongIntMap cells) {
		List<CellsS2C> packets = new ArrayList<>();
		int n = cells.size();

		if (n == 0) {
			return packets;
		}

		long[][] keys = {new long[Math.min(n, MapDrawPayloads.MAX_CELLS_S2C)]};
		int[][] values = {new int[keys[0].length]};
		int[] i = {0};
		int[] remaining = {n};

		cells.forEach((k, v) -> {
			keys[0][i[0]] = k;
			values[0][i[0]] = v;
			i[0]++;
			remaining[0]--;

			if (i[0] == keys[0].length) {
				packets.add(new CellsS2C(layer, keys[0], values[0]));

				if (remaining[0] > 0) {
					keys[0] = new long[Math.min(remaining[0], MapDrawPayloads.MAX_CELLS_S2C)];
					values[0] = new int[keys[0].length];
					i[0] = 0;
				}
			}
		});

		return packets;
	}

	private static void broadcast(CustomPacketPayload payload, UUID except) {
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
		LongIntMap all = new LongIntMap(layer.size());
		layer.forEach(all::put);

		for (CellsS2C packet : split(layer.meta().id(), all)) {
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
