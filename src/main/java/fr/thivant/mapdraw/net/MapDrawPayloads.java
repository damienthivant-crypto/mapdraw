package fr.thivant.mapdraw.net;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import fr.thivant.mapdraw.MapDraw;
import fr.thivant.mapdraw.core.LayerMeta;

/**
 * Tous les paquets réseau du mod.
 * <ul>
 *   <li>C2S = client vers serveur, S2C = serveur vers client.</li>
 *   <li>Les cases voyagent en lots : clés (long) + couleurs (int, {@code -1} = effacer).</li>
 * </ul>
 */
public final class MapDrawPayloads {
	/** Version du protocole : à incrémenter si le format change. */
	public static final int PROTOCOL = 1;
	/** Cases max par paquet client -> serveur (limite vanilla ~32 Ko par paquet entrant). */
	public static final int MAX_CELLS_C2S = 2000;
	/** Cases max par paquet serveur -> client. */
	public static final int MAX_CELLS_S2C = 20000;

	private MapDrawPayloads() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(HelloS2C.TYPE, HelloS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LayerMetaS2C.TYPE, LayerMetaS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LayerDeleteS2C.TYPE, LayerDeleteS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CellsS2C.TYPE, CellsS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MessageS2C.TYPE, MessageS2C.CODEC);

		PayloadTypeRegistry.serverboundPlay().register(LayerMetaC2S.TYPE, LayerMetaC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LayerDeleteC2S.TYPE, LayerDeleteC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CellsC2S.TYPE, CellsC2S.CODEC);
	}

	// ------------------------------------------------------------------ helpers

	static void writeMeta(RegistryFriendlyByteBuf buf, LayerMeta m) {
		buf.writeUUID(m.id());
		buf.writeUtf(m.name(), 128);
		buf.writeUUID(m.owner());
		buf.writeUtf(m.ownerName(), 64);
		buf.writeUtf(m.dimension(), 256);
		buf.writeBoolean(m.shared());
	}

	static LayerMeta readMeta(RegistryFriendlyByteBuf buf) {
		UUID id = buf.readUUID();
		String name = buf.readUtf(128);
		UUID owner = buf.readUUID();
		String ownerName = buf.readUtf(64);
		String dimension = buf.readUtf(256);
		boolean shared = buf.readBoolean();
		return new LayerMeta(id, name, owner, ownerName, dimension, shared);
	}

	static void writeCells(RegistryFriendlyByteBuf buf, long[] keys, int[] colors) {
		buf.writeVarInt(keys.length);

		for (int i = 0; i < keys.length; i++) {
			buf.writeLong(keys[i]);
			buf.writeInt(colors[i]);
		}
	}

	static long[] readKeys(RegistryFriendlyByteBuf buf, int[][] colorsOut, int max) {
		int n = buf.readVarInt();

		if (n < 0 || n > max) {
			throw new IllegalArgumentException("MapDraw : lot de cases trop grand (" + n + ")");
		}

		long[] keys = new long[n];
		int[] colors = new int[n];

		for (int i = 0; i < n; i++) {
			keys[i] = buf.readLong();
			colors[i] = buf.readInt();
		}

		colorsOut[0] = colors;
		return keys;
	}

	// ------------------------------------------------------------------ S2C

	/** Le serveur annonce qu'il a le mod, et si le joueur est OP. */
	public record HelloS2C(int protocol, boolean operator) implements CustomPacketPayload {
		public static final Type<HelloS2C> TYPE = new Type<>(MapDraw.id("hello"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HelloS2C> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.protocol);
					buf.writeBoolean(p.operator);
				},
				buf -> new HelloS2C(buf.readVarInt(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Création ou mise à jour d'un calque partagé. */
	public record LayerMetaS2C(LayerMeta meta) implements CustomPacketPayload {
		public static final Type<LayerMetaS2C> TYPE = new Type<>(MapDraw.id("layer_meta_s2c"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LayerMetaS2C> CODEC = StreamCodec.of(
				(buf, p) -> writeMeta(buf, p.meta),
				buf -> new LayerMetaS2C(readMeta(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Suppression d'un calque partagé. */
	public record LayerDeleteS2C(UUID layer) implements CustomPacketPayload {
		public static final Type<LayerDeleteS2C> TYPE = new Type<>(MapDraw.id("layer_delete_s2c"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LayerDeleteS2C> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeUUID(p.layer),
				buf -> new LayerDeleteS2C(buf.readUUID()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Lot de cases ajoutées / modifiées / effacées sur un calque partagé. */
	public record CellsS2C(UUID layer, long[] keys, int[] colors) implements CustomPacketPayload {
		public static final Type<CellsS2C> TYPE = new Type<>(MapDraw.id("cells_s2c"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CellsS2C> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUUID(p.layer);
					writeCells(buf, p.keys, p.colors);
				},
				buf -> {
					UUID layer = buf.readUUID();
					int[][] colors = new int[1][];
					long[] keys = readKeys(buf, colors, MAX_CELLS_S2C);
					return new CellsS2C(layer, keys, colors[0]);
				});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Message à afficher (refus de permission, etc.). */
	public record MessageS2C(String text) implements CustomPacketPayload {
		public static final Type<MessageS2C> TYPE = new Type<>(MapDraw.id("message"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MessageS2C> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeUtf(p.text, 512),
				buf -> new MessageS2C(buf.readUtf(512)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ C2S

	/** Le client crée ou modifie (nom, partage) un calque partagé. */
	public record LayerMetaC2S(LayerMeta meta) implements CustomPacketPayload {
		public static final Type<LayerMetaC2S> TYPE = new Type<>(MapDraw.id("layer_meta_c2s"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LayerMetaC2S> CODEC = StreamCodec.of(
				(buf, p) -> writeMeta(buf, p.meta),
				buf -> new LayerMetaC2S(readMeta(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Le client supprime (ou rend privé) un calque partagé. */
	public record LayerDeleteC2S(UUID layer) implements CustomPacketPayload {
		public static final Type<LayerDeleteC2S> TYPE = new Type<>(MapDraw.id("layer_delete_c2s"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LayerDeleteC2S> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeUUID(p.layer),
				buf -> new LayerDeleteC2S(buf.readUUID()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Lot de cases modifiées par le client sur un calque partagé. */
	public record CellsC2S(UUID layer, long[] keys, int[] colors) implements CustomPacketPayload {
		public static final Type<CellsC2S> TYPE = new Type<>(MapDraw.id("cells_c2s"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CellsC2S> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUUID(p.layer);
					writeCells(buf, p.keys, p.colors);
				},
				buf -> {
					UUID layer = buf.readUUID();
					int[][] colors = new int[1][];
					long[] keys = readKeys(buf, colors, MAX_CELLS_C2S);
					return new CellsC2S(layer, keys, colors[0]);
				});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
