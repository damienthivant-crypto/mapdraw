package fr.thivant.mapdraw.paper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerJson;
import fr.thivant.mapdraw.core.LayerMeta;

/**
 * Version Paper du serveur MapDraw : même protocole réseau que le mod Fabric.
 * <p>
 * Stocke les calques partagés (un fichier JSON par dimension dans {@code plugins/MapDraw/layers/}),
 * vérifie les droits (propriétaire ou OP) et diffuse les modifications groupées à chaque tick.
 * Les joueurs doivent avoir le mod MapDraw côté client.
 */
public final class MapDrawPaper extends JavaPlugin implements PluginMessageListener, Listener {
	static final int PROTOCOL = 2;
	private static final int MAX_CELLS_C2S = 2000;
	private static final int MAX_CELLS_S2C = 20000;
	private static final int MAX_LAYERS_PER_PLAYER = 64;
	private static final int MAX_CELLS_PER_LAYER = 262_144;
	private static final int SAVE_INTERVAL_TICKS = 200;
	private static final UUID NOBODY = new UUID(0L, 0L);

	// Canaux = identifiants des paquets du mod
	static final String HELLO = "mapdraw:hello";
	static final String LAYER_META_S2C = "mapdraw:layer_meta_s2c";
	static final String LAYER_DELETE_S2C = "mapdraw:layer_delete_s2c";
	static final String CELLS_S2C = "mapdraw:cells_s2c";
	static final String MESSAGE = "mapdraw:message";
	static final String LAYER_META_C2S = "mapdraw:layer_meta_c2s";
	static final String LAYER_DELETE_C2S = "mapdraw:layer_delete_c2s";
	static final String CELLS_C2S = "mapdraw:cells_c2s";

	private final Map<UUID, LayerData> layers = new LinkedHashMap<>();
	private final Set<String> dirtyDimensions = new HashSet<>();
	private final Map<UUID, Map<UUID, LinkedHashMap<Long, Integer>>> pending = new LinkedHashMap<>();
	private final Map<UUID, Long> lastError = new HashMap<>();
	private final Set<UUID> welcomed = new HashSet<>();
	private Path dir;
	private int tick;

	@Override
	public void onEnable() {
		dir = getDataFolder().toPath().resolve("layers");
		load();

		var messenger = getServer().getMessenger();

		for (String out : List.of(HELLO, LAYER_META_S2C, LAYER_DELETE_S2C, CELLS_S2C, MESSAGE)) {
			messenger.registerOutgoingPluginChannel(this, out);
		}

		for (String in : List.of(LAYER_META_C2S, LAYER_DELETE_C2S, CELLS_C2S)) {
			messenger.registerIncomingPluginChannel(this, in, this);
		}

		getServer().getPluginManager().registerEvents(this, this);

		Bukkit.getScheduler().runTaskTimer(this, () -> {
			flush();

			if (++tick >= SAVE_INTERVAL_TICKS) {
				tick = 0;
				saveDirty();
			}
		}, 1L, 1L);

		// Joueurs déjà connectés (rechargement du plugin)
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p.getListeningPluginChannels().contains(HELLO)) {
				sendFullState(p);
			}
		}

		getLogger().info("MapDraw (Paper) prêt : " + layers.size() + " calque(s) partagé(s), protocole " + PROTOCOL);
	}

	@Override
	public void onDisable() {
		flush();
		saveDirty();
	}

	// ------------------------------------------------------------------ événements

	/** Le client déclare ses canaux juste après la connexion : on lui envoie alors l'état complet. */
	@EventHandler
	public void onRegister(PlayerRegisterChannelEvent event) {
		if (HELLO.equals(event.getChannel()) && welcomed.add(event.getPlayer().getUniqueId())) {
			sendFullState(event.getPlayer());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		welcomed.remove(event.getPlayer().getUniqueId());
		lastError.remove(event.getPlayer().getUniqueId());
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		try {
			Wire.Reader in = new Wire.Reader(message);

			switch (channel) {
				case LAYER_META_C2S -> onLayerMeta(player, in.meta());
				case LAYER_DELETE_C2S -> onLayerDelete(player, in.uuid());
				case CELLS_C2S -> {
					UUID layer = in.uuid();
					int n = in.varInt();

					if (n < 0 || n > MAX_CELLS_C2S) {
						return;
					}

					long[] keys = new long[n];
					int[] colors = new int[n];

					for (int i = 0; i < n; i++) {
						keys[i] = in.i64();
						colors[i] = in.i32();
					}

					onCells(player, layer, keys, colors);
				}
				default -> {
				}
			}
		} catch (RuntimeException e) {
			getLogger().log(Level.WARNING, "Paquet MapDraw invalide de " + player.getName() + " sur " + channel, e);
		}
	}

	// ------------------------------------------------------------------ logique (identique au serveur Fabric)

	private boolean canEdit(Player player, LayerData layer) {
		return layer.meta().owner().equals(player.getUniqueId()) || player.isOp();
	}

	private void onLayerMeta(Player player, LayerMeta meta) {
		LayerData existing = layers.get(meta.id());

		if (existing == null) {
			if (!meta.shared()) {
				return;
			}

			long owned = layers.values().stream().filter(l -> l.meta().owner().equals(player.getUniqueId())).count();

			if (owned >= MAX_LAYERS_PER_PLAYER) {
				error(player, "Limite de " + MAX_LAYERS_PER_PLAYER + " calques partagés atteinte.");
				send(player, LAYER_DELETE_S2C, new Wire.Writer().uuid(meta.id()).bytes());
				return;
			}

			LayerMeta created = new LayerMeta(meta.id(), meta.name(), player.getUniqueId(), player.getName(), meta.dimension(), true);
			layers.put(created.id(), new LayerData(created));
			dirtyDimensions.add(created.dimension());
			broadcast(LAYER_META_S2C, new Wire.Writer().meta(created).bytes(), player.getUniqueId());
			return;
		}

		if (!canEdit(player, existing)) {
			error(player, "Le calque « " + existing.meta().name() + " » appartient à " + existing.meta().ownerName() + ".");
			send(player, LAYER_META_S2C, new Wire.Writer().meta(existing.meta()).bytes());
			return;
		}

		LayerMeta updated = existing.meta().withName(meta.name());
		existing.setMeta(updated);
		dirtyDimensions.add(updated.dimension());
		broadcast(LAYER_META_S2C, new Wire.Writer().meta(updated).bytes(), NOBODY);
	}

	private void onLayerDelete(Player player, UUID id) {
		LayerData existing = layers.get(id);

		if (existing == null) {
			return;
		}

		if (!canEdit(player, existing)) {
			error(player, "Seul " + existing.meta().ownerName() + " (ou un OP) peut supprimer « " + existing.meta().name() + " ».");
			resync(player, existing);
			return;
		}

		layers.remove(id);
		pending.remove(id);
		dirtyDimensions.add(existing.meta().dimension());
		broadcast(LAYER_DELETE_S2C, new Wire.Writer().uuid(id).bytes(), player.getUniqueId());
	}

	private void onCells(Player player, UUID layerId, long[] keys, int[] colors) {
		LayerData layer = layers.get(layerId);

		if (layer == null) {
			return;
		}

		if (!canEdit(player, layer)) {
			error(player, "Vous ne pouvez pas modifier « " + layer.meta().name() + " » (propriétaire : " + layer.meta().ownerName() + ").");
			resync(player, layer);
			return;
		}

		LinkedHashMap<Long, Integer> out = pending
				.computeIfAbsent(layerId, k -> new LinkedHashMap<>())
				.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
		boolean refused = false;

		for (int i = 0; i < keys.length; i++) {
			int color = colors[i] == LayerData.ERASE ? LayerData.ERASE : (colors[i] & fr.thivant.mapdraw.core.CellValue.MASK);

			if (color != LayerData.ERASE && layer.size() >= MAX_CELLS_PER_LAYER && layer.get(keys[i]) == null) {
				refused = true;
				continue;
			}

			layer.set(keys[i], color);
			out.put(keys[i], color);
		}

		dirtyDimensions.add(layer.meta().dimension());

		if (refused) {
			error(player, "Calque plein (" + MAX_CELLS_PER_LAYER + " blocs max).");
		}
	}

	// ------------------------------------------------------------------ envoi

	private void flush() {
		if (pending.isEmpty()) {
			return;
		}

		for (Map.Entry<UUID, Map<UUID, LinkedHashMap<Long, Integer>>> byLayer : pending.entrySet()) {
			for (Map.Entry<UUID, LinkedHashMap<Long, Integer>> bySender : byLayer.getValue().entrySet()) {
				for (byte[] packet : cellPackets(byLayer.getKey(), bySender.getValue())) {
					broadcast(CELLS_S2C, packet, bySender.getKey());
				}
			}
		}

		pending.clear();
	}

	private static List<byte[]> cellPackets(UUID layer, Map<Long, Integer> cells) {
		List<byte[]> packets = new ArrayList<>();
		List<Map.Entry<Long, Integer>> entries = new ArrayList<>(cells.entrySet());

		for (int start = 0; start < entries.size(); start += MAX_CELLS_S2C) {
			int end = Math.min(entries.size(), start + MAX_CELLS_S2C);
			Wire.Writer w = new Wire.Writer().uuid(layer).varInt(end - start);

			for (int i = start; i < end; i++) {
				w.i64(entries.get(i).getKey()).i32(entries.get(i).getValue());
			}

			packets.add(w.bytes());
		}

		return packets;
	}

	private void send(Player player, String channel, byte[] data) {
		if (player.getListeningPluginChannels().contains(channel)) {
			player.sendPluginMessage(this, channel, data);
		}
	}

	private void broadcast(String channel, byte[] data, UUID except) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!p.getUniqueId().equals(except)) {
				send(p, channel, data);
			}
		}
	}

	private void sendLayer(Player player, LayerData layer) {
		send(player, LAYER_META_S2C, new Wire.Writer().meta(layer.meta()).bytes());

		for (byte[] packet : cellPackets(layer.meta().id(), layer.snapshot())) {
			send(player, CELLS_S2C, packet);
		}
	}

	private void resync(Player player, LayerData layer) {
		send(player, LAYER_DELETE_S2C, new Wire.Writer().uuid(layer.meta().id()).bytes());
		sendLayer(player, layer);
	}

	private void sendFullState(Player player) {
		send(player, HELLO, new Wire.Writer().varInt(PROTOCOL).bool(player.isOp()).bytes());

		for (LayerData layer : layers.values()) {
			sendLayer(player, layer);
		}
	}

	private void error(Player player, String message) {
		long now = System.currentTimeMillis();
		Long last = lastError.get(player.getUniqueId());

		if (last != null && now - last < 1000) {
			return;
		}

		lastError.put(player.getUniqueId(), now);
		send(player, MESSAGE, new Wire.Writer().utf(message).bytes());
	}

	// ------------------------------------------------------------------ stockage

	private void load() {
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
					getLogger().log(Level.SEVERE, "Impossible de lire " + file, e);
				}
			}
		} catch (IOException e) {
			getLogger().log(Level.SEVERE, "Impossible de lister " + dir, e);
		}
	}

	private void saveDirty() {
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
				getLogger().log(Level.SEVERE, "Impossible d'écrire " + file, e);
			}
		}
	}
}
