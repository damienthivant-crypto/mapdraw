package fr.thivant.mapdraw.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import fr.thivant.mapdraw.core.CellKey;
import fr.thivant.mapdraw.core.LayerData;
import fr.thivant.mapdraw.core.LayerMeta;

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

		int maxCellsPerPacket();
	}

	/** Écoute des changements (rendu monde, overlays JourneyMap, écran). */
	public interface Listener {
		void chunkChanged(String dimension, long chunkKey);

		default void layersChanged() {
		}
	}

	/** Une action annulable : pour chaque case, couleur avant / après (null = vide). */
	static final class Action {
		final UUID layer;
		final LinkedHashMap<Long, Integer[]> changes = new LinkedHashMap<>();

		Action(UUID layer) {
			this.layer = layer;
		}

		void record(long key, Integer before, Integer after) {
			Integer[] change = changes.get(key);

			if (change == null) {
				changes.put(key, new Integer[] {before, after});
			} else {
				change[1] = after;
			}
		}

		boolean isEmpty() {
			changes.values().removeIf(c -> c[0] == null ? c[1] == null : c[0].equals(c[1]));
			return changes.isEmpty();
		}
	}

	private final LinkedHashMap<UUID, LayerData> layers = new LinkedHashMap<>();
	private final Set<UUID> hidden = new HashSet<>();
	/** Calques visibles sur la carte mais pas dans le monde (réglage local). */
	private final Set<UUID> worldHidden = new HashSet<>();
	private final List<Listener> listeners = new ArrayList<>();
	private final Deque<Action> undo = new ArrayDeque<>();
	private final Deque<Action> redo = new ArrayDeque<>();
	private final Map<UUID, LinkedHashMap<Long, Integer>> outgoing = new LinkedHashMap<>();

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
				merged.putAll(layer.cellsInChunk(chunkKey));
			}
		}

		return merged;
	}

	/** Comme mergedChunk, mais sans les calques masqués dans le monde (rendu des blocs fantômes). */
	public Map<Long, Integer> mergedChunkWorld(String dimension, long chunkKey) {
		Map<Long, Integer> merged = new HashMap<>();

		for (LayerData layer : layers.values()) {
			UUID id = layer.meta().id();

			if (!hidden.contains(id) && !worldHidden.contains(id) && layer.meta().dimension().equals(dimension)) {
				merged.putAll(layer.cellsInChunk(chunkKey));
			}
		}

		return merged;
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

	public LayerData createLayer(String name, String dimension) {
		LayerData layer = new LayerData(new LayerMeta(UUID.randomUUID(), name, selfId, selfName, dimension, false));
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
		source.forEach(layer::set);
		layers.put(layer.meta().id(), layer);
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

		layers.put(layer.meta().id(), layer);
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
			LinkedHashMap<Long, Integer> out = outgoing.computeIfAbsent(id, k -> new LinkedHashMap<>());
			layer.forEach(out::put);
		} else {
			outgoing.remove(id);
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
			undo.push(done);
			redo.clear();

			while (undo.size() > HISTORY_LIMIT) {
				undo.removeLast();
			}
		}
	}

	/** Peint des cases sur le calque de l'action en cours. */
	public void paint(Collection<Long> keys, int color) {
		applyToCurrent(keys, color & fr.thivant.mapdraw.core.CellValue.MASK);
	}

	/** Efface des cases sur le calque de l'action en cours. */
	public void erase(Collection<Long> keys) {
		applyToCurrent(keys, null);
	}

	private void applyToCurrent(Collection<Long> keys, Integer color) {
		if (current == null) {
			return;
		}

		LayerData layer = layers.get(current.layer);

		if (layer == null) {
			current = null;
			return;
		}

		Set<Long> touched = new HashSet<>();

		for (long key : keys) {
			Integer before = layer.set(key, color);

			if (before == null ? color == null : before.equals(color)) {
				continue;
			}

			current.record(key, before, color);
			queueOutgoing(layer, key, color);
			touched.add(CellKey.chunkOf(key));
		}

		afterEdit(layer, touched);
	}

	/** Efface tout le calque (annulable). */
	public boolean clearLayer(UUID id) {
		LayerData layer = layers.get(id);

		if (layer == null || !canEdit(layer)) {
			message("Vous ne pouvez pas effacer ce calque.");
			return false;
		}

		beginAction(id);
		erase(new ArrayList<>(layer.snapshot().keySet()));
		endAction();
		return true;
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

			for (Map.Entry<Long, Integer[]> e : action.changes.entrySet()) {
				Integer target = backwards ? e.getValue()[0] : e.getValue()[1];
				layer.set(e.getKey(), target);
				queueOutgoing(layer, e.getKey(), target);
				touched.add(CellKey.chunkOf(e.getKey()));
			}

			afterEdit(layer, touched);
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

	private void queueOutgoing(LayerData layer, long key, Integer color) {
		if (layer.meta().shared() && serverMode) {
			outgoing.computeIfAbsent(layer.meta().id(), k -> new LinkedHashMap<>())
					.put(key, color == null ? LayerData.ERASE : color);
		}
	}

	/** Envoie les modifications en attente (appelé à chaque tick client). */
	public void flushOutgoing() {
		if (outgoing.isEmpty() || sink == null) {
			return;
		}

		int max = sink.maxCellsPerPacket();
		Iterator<Map.Entry<UUID, LinkedHashMap<Long, Integer>>> it = outgoing.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<UUID, LinkedHashMap<Long, Integer>> e = it.next();
			it.remove();
			LinkedHashMap<Long, Integer> cells = e.getValue();

			if (cells.isEmpty()) {
				continue;
			}

			long[] keys = new long[Math.min(max, cells.size())];
			int[] colors = new int[keys.length];
			int i = 0;
			int remaining = cells.size();

			for (Map.Entry<Long, Integer> c : cells.entrySet()) {
				keys[i] = c.getKey();
				colors[i] = c.getValue();
				i++;
				remaining--;

				if (i == keys.length) {
					sink.sendCells(e.getKey(), keys, colors);

					if (remaining == 0) {
						break;
					}

					keys = new long[Math.min(max, remaining)];
					colors = new int[keys.length];
					i = 0;
				}
			}
		}
	}

	// ------------------------------------------------------------------ réception serveur

	public void remoteMeta(LayerMeta meta) {
		LayerData layer = layers.get(meta.id());

		if (layer == null) {
			layer = new LayerData(meta.withShared(true));
			layers.put(meta.id(), layer);
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
