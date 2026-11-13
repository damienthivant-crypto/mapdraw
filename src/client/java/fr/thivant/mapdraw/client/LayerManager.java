package fr.thivant.mapdraw.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.CellValue;
import fr.thivant.mapdraw.core.LongIntMap;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerMeta;
import fr.thivant.mapdraw.core.Zone;

/**
 * État client de tous les calques (privés + partagés), historique annuler/rétablir,
 * file d'envoi réseau groupée. Aucune dépendance à Minecraft : testable seul.
 */
public final class LayerManager {
	public static final int HISTORY_LIMIT = 200;

	/** Sortie réseau (implémentée par ClientNet). */
	public interface NetSink {
		void sendMeta(LayerMeta meta);

		void sendDelete(UUID layer);

		void sendCells(UUID layer, long[] keys, int[] colors);

		default void sendZone(UUID layer, Zone zone) {
		}

		default void sendZoneDelete(UUID layer, UUID zone) {
		}

		int maxCellsPerPacket();
	}

	/** Écoute des changements (rendu monde, overlays JourneyMap, écran). */
	public interface Listener {
		void chunkChanged(String dimension, long chunkKey);

		default void layersChanged() {
		}
	}

	/** Modification d'une zone : état avant / après (null = absente). */
	record ZoneChange(UUID id, Zone before, Zone after) {
	}

	/** Une zone et son calque (résultat d'une sélection). */
	public record ZoneRef(LayerData layer, Zone zone) {
	}

	/**
	 * Une action annulable : pour chaque case, valeur avant / après (EMPTY = vide), stockage primitif ;
	 * plus les zones créées / modifiées / supprimées.
	 */
	static final class Action {
		static final int EMPTY = -2;
		final UUID layer;
		final LongIntMap before = new LongIntMap();
		final LongIntMap after = new LongIntMap();
		final List<ZoneChange> zones = new ArrayList<>();

		Action(UUID layer) {
			this.layer = layer;
		}

		void record(long key, int beforeValue, int afterValue) {
			if (before.get(key) == LongIntMap.NONE) {
				before.put(key, beforeValue);
			}

			after.put(key, afterValue);
		}

		boolean isEmpty() {
			if (!zones.isEmpty()) {
				return false;
			}

			int[] changed = {0};
			after.forEach((k, v) -> {
				if (before.get(k) != v) {
					changed[0]++;
				}
			});
			return changed[0] == 0;
		}
	}

	/** Nombre maximal de paquets envoyés par tick (évite d'être expulsé par l'anti-spam du serveur). */
	public static final int MAX_PACKETS_PER_TICK = 8;

	/** Paquet de cases prêt à partir. */
	private record OutPacket(UUID layer, long[] keys, int[] colors) {
	}

	private final LinkedHashMap<UUID, LayerData> layers = new LinkedHashMap<>();
	private final Set<UUID> hidden = new HashSet<>();
	/** Calques visibles sur la carte mais pas dans le monde (réglage local). */
	private final Set<UUID> worldHidden = new HashSet<>();
	private final List<Listener> listeners = new ArrayList<>();
	private final Deque<Action> undo = new ArrayDeque<>();
	private final Deque<Action> redo = new ArrayDeque<>();
	private final Map<UUID, LongIntMap> outgoing = new LinkedHashMap<>();
	private final Deque<OutPacket> sendQueue = new ArrayDeque<>();

	private Action current;
	private UUID active;
	private UUID selfId = new UUID(0L, 0L);
	private String selfName = "?";
	private boolean serverMode;
	private boolean operator;
	private NetSink sink;
	private boolean privateDirty;
	private boolean viewDirty;
	private String lastMessage;

	// ------------------------------------------------------------------ configuration

	public void setSelf(UUID id, String name) {
		this.selfId = id;
		this.selfName = name;
	}

	public UUID selfId() {
		return selfId;
	}

	public void setSink(NetSink sink) {
		this.sink = sink;
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public boolean serverMode() {
		return serverMode;
	}

	public boolean operator() {
		return operator;
	}

	/** Le serveur a le mod : le partage devient possible. */
	public void onServerHello(boolean isOperator) {
		this.serverMode = true;
		this.operator = isOperator;
		notifyLayers();
	}

	/** Fin de session : on oublie tout (les calques privés ont été sauvegardés avant). */
	public void reset() {
		Set<UUID> ids = new HashSet<>(layers.keySet());

		for (UUID id : ids) {
			removeLocal(id);
		}

		hidden.clear();
		worldHidden.clear();
		undo.clear();
		redo.clear();
		outgoing.clear();
		sendQueue.clear();
		current = null;
		active = null;
		serverMode = false;
		operator = false;
		privateDirty = false;
		viewDirty = false;
		notifyLayers();
	}

	// ------------------------------------------------------------------ lecture

	public Collection<LayerData> all() {
		return layers.values();
	}

	public LayerData get(UUID id) {
		return id == null ? null : layers.get(id);
	}

	public List<LayerData> inDimension(String dimension) {
		List<LayerData> out = new ArrayList<>();

		for (LayerData layer : layers.values()) {
			if (layer.meta().dimension().equals(dimension)) {
				out.add(layer);
			}
		}

		return out;
	}

	public List<LayerData> privateLayers() {
		List<LayerData> out = new ArrayList<>();

		for (LayerData layer : layers.values()) {
			if (!layer.meta().shared()) {
				out.add(layer);
			}
		}

		return out;
	}

	public boolean isHidden(UUID id) {
		return hidden.contains(id);
	}

	public Set<UUID> hiddenIds() {
		return hidden;
	}

	public boolean isWorldHidden(UUID id) {
		return worldHidden.contains(id);
	}

	public Set<UUID> worldHiddenIds() {
		return worldHidden;
	}

	/** Afficher / ne pas afficher ce calque dans le monde (il reste sur la carte). */
	public void toggleWorld(UUID id) {
		LayerData layer = layers.get(id);

		if (layer == null) {
			return;
		}

		if (!worldHidden.remove(id)) {
			worldHidden.add(id);
		}

		viewDirty = true;
		notifyAllChunks(layer);
		notifyLayers();
	}

	public boolean isOwn(LayerData layer) {
		return layer.meta().owner().equals(selfId);
	}

	public boolean canEdit(LayerData layer) {
		if (layer == null) {
			return false;
		}

		if (!layer.meta().shared()) {
			return true;
		}

		return isOwn(layer) || operator;
	}

	public UUID activeId() {
		return active;
	}

	public LayerData active() {
		return get(active);
	}

	/** Couleurs fusionnées des calques visibles d'une dimension pour un chunk (le dernier calque l'emporte). */
	public Map<Long, Integer> mergedChunk(String dimension, long chunkKey) {
		Map<Long, Integer> merged = new HashMap<>();

		for (LayerData layer : layers.values()) {
			if (!hidden.contains(layer.meta().id()) && layer.meta().dimension().equals(dimension)) {
				layer.forEachInChunk(chunkKey, (k, v) -> {
					if (!CellValue.hiddenOnMap(v)) {
						merged.put(k, v);
					}
				});
			}
		}

		return merged;
	}

	/**
	 * Remplit une grille 16x16 (index (z&15)*16 + (x&15)) avec les valeurs fusionnées des calques visibles,
	 * {@link LongIntMap#NONE} pour une case vide. Sans allocation d'objets.
	 */
	public void chunkGrid(String dimension, long chunkKey, int[] grid) {
		java.util.Arrays.fill(grid, LongIntMap.NONE);

		for (LayerData layer : layers.values()) {
			if (!hidden.contains(layer.meta().id()) && layer.meta().dimension().equals(dimension)) {
				layer.forEachInChunk(chunkKey, (k, v) -> {
					if (!CellValue.hiddenOnMap(v)) {
						grid[((CellKey.z(k) & 15) << 4) | (CellKey.x(k) & 15)] = v;
					}
				});
			}
		}
	}

	/** Comme mergedChunk, mais sans les calques masqués dans le monde (rendu des blocs fantômes). */
	public Map<Long, Integer> mergedChunkWorld(String dimension, long chunkKey) {
		Map<Long, Integer> merged = new HashMap<>();

		for (LayerData layer : layers.values()) {
			UUID id = layer.meta().id();

			if (!hidden.contains(id) && !worldHidden.contains(id) && layer.meta().dimension().equals(dimension)) {
				layer.forEachInChunk(chunkKey, (k, v) -> {
					if (!CellValue.hiddenInWorld(v)) {
						merged.put(k, v);
					}
				});
			}
		}

		return merged;
	}

	/** Le chunk contient-il au moins une case visible ? */
	public boolean hasCells(String dimension, long chunkKey) {
		for (LayerData layer : layers.values()) {
			if (!hidden.contains(layer.meta().id()) && layer.meta().dimension().equals(dimension) && layer.hasChunk(chunkKey)) {
				return true;
			}
		}

		return false;
	}

	/** Tous les chunks d'une dimension qui contiennent au moins une case visible. */
	public Set<Long> visibleChunks(String dimension) {
		Set<Long> out = new HashSet<>();

		for (LayerData layer : layers.values()) {
			if (!hidden.contains(layer.meta().id()) && layer.meta().dimension().equals(dimension)) {
				out.addAll(layer.chunkKeys());
			}
		}

		return out;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	/** Dernier message à afficher au joueur (consommé par l'appelant). */
	public String pollMessage() {
		String m = lastMessage;
		lastMessage = null;
		return m;
	}

	private void message(String m) {
		lastMessage = m;
	}

	/** Message à afficher au joueur (utilisé par les outils). */
	public void info(String m) {
		lastMessage = m;
	}

	// ------------------------------------------------------------------ gestion des calques

	/** Ajoute un calque à la liste (active le calcul des blocs des zones). */
	private void adopt(LayerData layer) {
		layer.enableZoneRaster();
		layers.put(layer.meta().id(), layer);
	}

	public LayerData createLayer(String name, String dimension) {
		LayerData layer = new LayerData(new LayerMeta(UUID.randomUUID(), name, selfId, selfName, dimension, false));
		layer.enableZoneRaster();
		layers.put(layer.meta().id(), layer);
		active = layer.meta().id();
		privateDirty = true;
		viewDirty = true;
		notifyLayers();
		return layer;
	}

	/** Ajoute un calque importé depuis un fichier : il devient un calque privé du joueur. */
	public LayerData importLayer(LayerData source, String dimension) {
		LayerData layer = new LayerData(new LayerMeta(UUID.randomUUID(), source.meta().name(), selfId, selfName, dimension, false));
		source.forEach(layer::setRaw);

		for (Zone z : source.zones()) {
			layer.putZone(new Zone(UUID.randomUUID(), z.kind(), z.points(), z.color(), z.width(), z.filled(), z.opacity(),
					z.showWorld(), z.showMap(), z.name()));
		}

		adopt(layer);
		active = layer.meta().id();
		privateDirty = true;
		viewDirty = true;
		notifyAllChunks(layer);
		notifyLayers();
		return layer;
	}

	/** Chargement d'un calque privé depuis le disque. */
	public void loadPrivate(LayerData layer) {
		if (layer.meta().shared()) {
			layer.setMeta(layer.meta().withShared(false));
		}

		adopt(layer);
		notifyAllChunks(layer);
		notifyLayers();
	}

	/** Restaure l'état d'affichage (calques masqués, calque actif). */
	public void restoreView(Collection<UUID> hiddenIds, Collection<UUID> worldHiddenIds, UUID activeId) {
		hidden.clear();
		hidden.addAll(hiddenIds);
		worldHidden.clear();
		worldHidden.addAll(worldHiddenIds);
		active = activeId;
		layers.values().forEach(this::notifyAllChunks);
		notifyLayers();
	}

	public void setActive(UUID id) {
		if (layers.containsKey(id)) {
			active = id;
			viewDirty = true;
			notifyLayers();
		}
	}

	/** Calque actif modifiable dans la dimension courante ; en crée un si besoin. */
	public LayerData ensureEditableActive(String dimension) {
		LayerData layer = active();

		if (layer != null && layer.meta().dimension().equals(dimension) && canEdit(layer)) {
			return layer;
		}

		if (layer != null && layer.meta().dimension().equals(dimension) && !canEdit(layer)) {
			message("Calque « " + layer.meta().name() + " » en lecture seule (propriétaire : " + layer.meta().ownerName() + ").");
			return null;
		}

		for (LayerData candidate : layers.values()) {
			if (candidate.meta().dimension().equals(dimension) && canEdit(candidate)) {
				active = candidate.meta().id();
				viewDirty = true;
				notifyLayers();
				return candidate;
			}
		}

		return createLayer("Calque " + (inDimension(dimension).size() + 1), dimension);
	}

	public void toggleHidden(UUID id) {
		LayerData layer = layers.get(id);

		if (layer == null) {
			return;
		}

		if (!hidden.remove(id)) {
			hidden.add(id);
		}

		viewDirty = true;
		notifyAllChunks(layer);
		notifyLayers();
	}

	public boolean rename(UUID id, String name) {
		LayerData layer = layers.get(id);

		if (layer == null || !canEdit(layer)) {
			message("Vous ne pouvez pas renommer ce calque.");
			return false;
		}

		layer.setMeta(layer.meta().withName(name));

		if (layer.meta().shared()) {
			if (sink != null) {
				sink.sendMeta(layer.meta());
			}
		} else {
			privateDirty = true;
		}

		notifyLayers();
		return true;
	}

	/** Partager (true) ou rendre privé (false). Réservé au propriétaire, nécessite le mod côté serveur. */
	public boolean setShared(UUID id, boolean shared) {
		LayerData layer = layers.get(id);

		if (layer == null || layer.meta().shared() == shared) {
			return false;
		}

		if (!serverMode || sink == null) {
			message("Le serveur n'a pas MapDraw : partage impossible (utilisez l'export .json).");
			return false;
		}

		if (!isOwn(layer)) {
			message("Seul le propriétaire peut changer le partage de ce calque.");
			return false;
		}

		layer.setMeta(layer.meta().withShared(shared));
		privateDirty = true;

		if (shared) {
			sink.sendMeta(layer.meta());
			LongIntMap out = outgoing.computeIfAbsent(id, k -> new LongIntMap(layer.size()));
			layer.forEach(out::put);

			for (Zone z : layer.zones()) {
				sink.sendZone(id, z);
			}
		} else {
			outgoing.remove(id);
			sendQueue.removeIf(p -> p.layer().equals(id));
			sink.sendDelete(id);
		}

		notifyLayers();
		return true;
	}

	public boolean delete(UUID id) {
		LayerData layer = layers.get(id);

		if (layer == null) {
			return false;
		}

		if (!canEdit(layer)) {
			message("Seul " + layer.meta().ownerName() + " (ou un OP) peut supprimer ce calque.");
			return false;
		}

		if (layer.meta().shared() && sink != null) {
			sink.sendDelete(id);
		} else {
			privateDirty = true;
		}

		removeLocal(id);
		notifyLayers();
		return true;
	}

	private void removeLocal(UUID id) {
		LayerData layer = layers.remove(id);

		if (layer == null) {
			return;
		}

		outgoing.remove(id);
		sendQueue.removeIf(p -> p.layer().equals(id));
		undo.removeIf(a -> a.layer.equals(id));
		redo.removeIf(a -> a.layer.equals(id));

		if (current != null && current.layer.equals(id)) {
			current = null;
		}

		if (id.equals(active)) {
			active = null;
		}

		notifyAllChunks(layer);
	}

	// ------------------------------------------------------------------ édition

	/** Début d'un trait (main levée, ligne, gomme…) : tout ce qui suit sera annulé d'un coup. */
	public void beginAction(UUID layerId) {
		endAction();
		LayerData layer = layers.get(layerId);

		if (layer != null && canEdit(layer)) {
			current = new Action(layerId);
		}
	}

	public boolean inAction() {
		return current != null;
	}

	public void endAction() {
		if (current == null) {
			return;
		}

		Action done = current;
		current = null;

		if (!done.isEmpty()) {
			pushHistory(done);
		}
	}

	/** Peint des cases sur le calque de l'action en cours. */
	public void paint(Collection<Long> keys, int value) {
		int v = value & CellValue.MASK;
		applyToCurrent(keys.size(), consumer -> keys.forEach(k -> consumer.accept(k, v)));
	}

	/** Peint un tableau de cases (grandes surfaces, sans objets intermédiaires). */
	public void paint(long[] keys, int value) {
		int v = value & CellValue.MASK;
		applyToCurrent(keys.length, consumer -> {
			for (long k : keys) {
				consumer.accept(k, v);
			}
		});
	}

	/** Efface des cases sur le calque de l'action en cours. */
	public void erase(Collection<Long> keys) {
		applyToCurrent(keys.size(), consumer -> keys.forEach(k -> consumer.accept(k, LayerData.ERASE)));
	}

	private interface CellSink {
		void accept(long key, int value);
	}

	private interface CellSource {
		void emit(CellSink sink);
	}

	private void applyToCurrent(int expected, CellSource source) {
		if (current == null) {
			return;
		}

		LayerData layer = layers.get(current.layer);

		if (layer == null) {
			current = null;
			return;
		}

		Set<Long> touched = new HashSet<>();
		Action action = current;
		LongIntMap out = queueFor(layer, expected);

		source.emit((key, value) -> {
			int before = layer.setRaw(key, value);
			int beforeV = before == LongIntMap.NONE ? Action.EMPTY : before;
			int afterV = value == LayerData.ERASE ? Action.EMPTY : value;

			if (beforeV == afterV) {
				return;
			}

			action.record(key, beforeV, afterV);

			if (out != null) {
				out.put(key, value);
			}

			touched.add(CellKey.chunkOf(key));
		});

		afterEdit(layer, touched);
	}

	/** File d'envoi du calque s'il est partagé, sinon null. */
	private LongIntMap queueFor(LayerData layer, int expected) {
		if (layer.meta().shared() && serverMode) {
			return outgoing.computeIfAbsent(layer.meta().id(), k -> new LongIntMap(Math.max(16, expected)));
		}

		return null;
	}

	/** Efface tout le calque (annulable). */
	public boolean clearLayer(UUID id) {
		LayerData layer = layers.get(id);

		if (layer == null || !canEdit(layer)) {
			message("Vous ne pouvez pas effacer ce calque.");
			return false;
		}

		beginAction(id);
		long[] keys = layer.toArrays().keys();
		applyToCurrent(keys.length, consumer -> {
			for (long k : keys) {
				consumer.accept(k, LayerData.ERASE);
			}
		});

		Action action = current;

		for (Zone z : new ArrayList<>(layer.zones())) {
			applyZone(layer, z.id(), null);

			if (action != null) {
				action.zones.add(new ZoneChange(z.id(), z, null));
			}
		}

		endAction();
		return true;
	}

	// ------------------------------------------------------------------ zones

	/** Zone sous le bloc (x, z) dans les calques visibles de la dimension (le plus haut d'abord). */
	public ZoneRef zoneAt(String dimension, int x, int z) {
		List<LayerData> list = new ArrayList<>(layers.values());
		ZoneRef readOnly = null;

		for (int i = list.size() - 1; i >= 0; i--) {
			LayerData layer = list.get(i);

			if (hidden.contains(layer.meta().id()) || !layer.meta().dimension().equals(dimension)) {
				continue;
			}

			Zone zone = layer.zoneAt(x, z);

			if (zone != null) {
				if (canEdit(layer)) {
					return new ZoneRef(layer, zone);
				}

				if (readOnly == null) {
					readOnly = new ZoneRef(layer, zone);
				}
			}
		}

		return readOnly;
	}

	/** Retrouve une zone par identifiant (null si elle a disparu). */
	public ZoneRef findZone(UUID layerId, UUID zoneId) {
		LayerData layer = layers.get(layerId);
		Zone zone = layer == null ? null : layer.zone(zoneId);
		return zone == null ? null : new ZoneRef(layer, zone);
	}

	/** Crée ou modifie une zone (annulable). */
	public boolean putZone(UUID layerId, Zone zone) {
		LayerData layer = layers.get(layerId);

		if (layer == null || !canEdit(layer)) {
			message("Ce calque est en lecture seule.");
			return false;
		}

		Zone before = layer.zone(zone.id());

		if (zone.equals(before)) {
			return false;
		}

		if (!zone.valid()) {
			message("Forme trop grande (4096 blocs de côté max) ou trop de sommets.");
			return false;
		}

		endAction();
		Action action = new Action(layerId);
		action.zones.add(new ZoneChange(zone.id(), before, zone));
		applyZone(layer, zone.id(), zone);
		pushHistory(action);
		return true;
	}

	/** Supprime une zone (annulable). */
	public boolean deleteZone(UUID layerId, UUID zoneId) {
		LayerData layer = layers.get(layerId);

		if (layer == null || !canEdit(layer)) {
			message("Ce calque est en lecture seule.");
			return false;
		}

		Zone before = layer.zone(zoneId);

		if (before == null) {
			return false;
		}

		endAction();
		Action action = new Action(layerId);
		action.zones.add(new ZoneChange(zoneId, before, null));
		applyZone(layer, zoneId, null);
		pushHistory(action);
		return true;
	}

	private void pushHistory(Action action) {
		undo.push(action);
		redo.clear();

		while (undo.size() > HISTORY_LIMIT) {
			undo.removeLast();
		}
	}

	/** Applique localement une zone (null = suppression), l'envoie si besoin, rafraîchit l'affichage. */
	private void applyZone(LayerData layer, UUID zoneId, Zone zone) {
		Set<Long> touched = zone == null ? layer.removeZone(zoneId) : layer.putZone(zone);
		UUID id = layer.meta().id();

		if (layer.meta().shared() && serverMode && sink != null) {
			if (zone == null) {
				sink.sendZoneDelete(id, zoneId);
			} else {
				sink.sendZone(id, zone);
			}
		}

		if (!layer.meta().shared()) {
			privateDirty = true;
		}

		if (!hidden.contains(id)) {
			for (long chunk : touched) {
				notifyChunk(layer.meta().dimension(), chunk);
			}
		}
	}

	public void remoteZone(UUID layerId, Zone zone) {
		LayerData layer = layers.get(layerId);

		if (layer == null) {
			return;
		}

		Set<Long> touched = layer.putZone(zone);

		if (!hidden.contains(layerId)) {
			for (long chunk : touched) {
				notifyChunk(layer.meta().dimension(), chunk);
			}
		}
	}

	public void remoteZoneDelete(UUID layerId, UUID zoneId) {
		LayerData layer = layers.get(layerId);

		if (layer == null) {
			return;
		}

		Set<Long> touched = layer.removeZone(zoneId);

		if (!hidden.contains(layerId)) {
			for (long chunk : touched) {
				notifyChunk(layer.meta().dimension(), chunk);
			}
		}
	}

	public boolean undo() {
		endAction();
		return replay(undo, redo, true);
	}

	public boolean redo() {
		endAction();
		return replay(redo, undo, false);
	}

	private boolean replay(Deque<Action> from, Deque<Action> to, boolean backwards) {
		while (!from.isEmpty()) {
			Action action = from.pop();
			LayerData layer = layers.get(action.layer);

			if (layer == null || !canEdit(layer)) {
				continue; // calque supprimé ou devenu non modifiable : on passe à l'action suivante
			}

			Set<Long> touched = new HashSet<>();
			LongIntMap out = queueFor(layer, action.after.size());
			action.after.forEach((key, afterV) -> {
				int target = backwards ? action.before.get(key) : afterV;
				int value = target == Action.EMPTY ? LayerData.ERASE : target;
				layer.setRaw(key, value);

				if (out != null) {
					out.put(key, value);
				}

				touched.add(CellKey.chunkOf(key));
			});

			afterEdit(layer, touched);

			List<ZoneChange> changes = new ArrayList<>(action.zones);

			if (backwards) {
				java.util.Collections.reverse(changes);
			}

			for (ZoneChange c : changes) {
				applyZone(layer, c.id(), backwards ? c.before() : c.after());
			}

			to.push(action);
			return true;
		}

		return false;
	}

	private void afterEdit(LayerData layer, Set<Long> touchedChunks) {
		if (touchedChunks.isEmpty()) {
			return;
		}

		if (!layer.meta().shared()) {
			privateDirty = true;
		}

		if (!hidden.contains(layer.meta().id())) {
			for (long chunk : touchedChunks) {
				notifyChunk(layer.meta().dimension(), chunk);
			}
		}
	}

	/** Blocs encore en attente d'envoi (pour l'affichage). */
	public int pendingUpload() {
		int n = 0;

		for (OutPacket p : sendQueue) {
			n += p.keys().length;
		}

		for (LongIntMap m : outgoing.values()) {
			n += m.size();
		}

		return n;
	}

	/**
	 * Envoie les modifications en attente (appelé à chaque tick client).
	 * Les gros lots sont découpés et étalés sur plusieurs ticks.
	 */
	public void flushOutgoing() {
		if (sink == null) {
			return;
		}

		if (!outgoing.isEmpty()) {
			int max = sink.maxCellsPerPacket();

			for (Map.Entry<UUID, LongIntMap> e : outgoing.entrySet()) {
				LongIntMap cells = e.getValue();

				if (cells.isEmpty()) {
					continue;
				}

				long[] keys = new long[Math.min(max, cells.size())];
				int[] colors = new int[keys.length];
				int[] idx = {0};
				int[] remaining = {cells.size()};
				UUID layer = e.getKey();
				long[][] kRef = {keys};
				int[][] cRef = {colors};

				cells.forEach((k, v) -> {
					kRef[0][idx[0]] = k;
					cRef[0][idx[0]] = v;
					idx[0]++;
					remaining[0]--;

					if (idx[0] == kRef[0].length) {
						sendQueue.add(new OutPacket(layer, kRef[0], cRef[0]));

						if (remaining[0] > 0) {
							kRef[0] = new long[Math.min(max, remaining[0])];
							cRef[0] = new int[kRef[0].length];
							idx[0] = 0;
						}
					}
				});
			}

			outgoing.clear();
		}

		for (int i = 0; i < MAX_PACKETS_PER_TICK && !sendQueue.isEmpty(); i++) {
			OutPacket p = sendQueue.poll();
			sink.sendCells(p.layer(), p.keys(), p.colors());
		}
	}

	// ------------------------------------------------------------------ réception serveur

	public void remoteMeta(LayerMeta meta) {
		LayerData layer = layers.get(meta.id());

		if (layer == null) {
			layer = new LayerData(meta.withShared(true));
			adopt(layer);
		} else {
			layer.setMeta(meta.withShared(true));
		}

		notifyLayers();
	}

	public void remoteDelete(UUID id) {
		removeLocal(id);
		notifyLayers();
	}

	public void remoteCells(UUID id, long[] keys, int[] colors) {
		LayerData layer = layers.get(id);

		if (layer == null) {
			return;
		}

		Set<Long> touched = new HashSet<>();
		int n = Math.min(keys.length, colors.length);

		for (int i = 0; i < n; i++) {
			layer.set(keys[i], colors[i] == LayerData.ERASE ? null : colors[i]);
			touched.add(CellKey.chunkOf(keys[i]));
		}

		if (!hidden.contains(id)) {
			for (long chunk : touched) {
				notifyChunk(layer.meta().dimension(), chunk);
			}
		}
	}

	// ------------------------------------------------------------------ sauvegarde

	public boolean consumePrivateDirty() {
		boolean d = privateDirty;
		privateDirty = false;
		return d;
	}

	public boolean consumeViewDirty() {
		boolean d = viewDirty;
		viewDirty = false;
		return d;
	}

	// ------------------------------------------------------------------ notifications

	private void notifyChunk(String dimension, long chunk) {
		for (Listener l : listeners) {
			l.chunkChanged(dimension, chunk);
		}
	}

	private void notifyAllChunks(LayerData layer) {
		for (long chunk : new ArrayList<>(layer.chunkKeys())) {
			notifyChunk(layer.meta().dimension(), chunk);
		}
	}

	private void notifyLayers() {
		for (Listener l : listeners) {
			l.layersChanged();
		}
	}
}
