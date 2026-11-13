package fr.thivant.mapdraw.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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
import fr.thivant.mapdraw.net.MapDrawPayloads.ZoneC2S;
import fr.thivant.mapdraw.net.MapDrawPayloads.ZoneDeleteC2S;
import fr.thivant.mapdraw.net.MapDrawPayloads.ZoneDeleteS2C;
import fr.thivant.mapdraw.net.MapDrawPayloads.ZoneS2C;
import fr.thivant.mapdraw.core.Zone;

/**
 * Réseau côté client. Si le serveur n'a pas le mod, aucun paquet n'est envoyé (mode local).
 */
public final class ClientNet implements LayerManager.NetSink {
	private ClientNet() {
	}

	public static void init(LayerManager layers) {
		layers.setSink(new ClientNet());

		ClientPlayNetworking.registerGlobalReceiver(HelloS2C.TYPE, (payload, context) -> {
			if (payload.protocol() != MapDrawPayloads.PROTOCOL) {
				chat("MapDraw : version différente sur le serveur (protocole " + payload.protocol() + "), partage désactivé.");
				return;
			}

			layers.onServerHello(payload.operator());
		});

		ClientPlayNetworking.registerGlobalReceiver(LayerMetaS2C.TYPE, (payload, context) -> layers.remoteMeta(payload.meta()));
		ClientPlayNetworking.registerGlobalReceiver(LayerDeleteS2C.TYPE, (payload, context) -> layers.remoteDelete(payload.layer()));
		ClientPlayNetworking.registerGlobalReceiver(CellsS2C.TYPE, (payload, context) -> layers.remoteCells(payload.layer(), payload.keys(), payload.colors()));
		ClientPlayNetworking.registerGlobalReceiver(ZoneS2C.TYPE, (payload, context) -> layers.remoteZone(payload.layer(), payload.zone()));
		ClientPlayNetworking.registerGlobalReceiver(ZoneDeleteS2C.TYPE, (payload, context) -> layers.remoteZoneDelete(payload.layer(), payload.zone()));
		ClientPlayNetworking.registerGlobalReceiver(MessageS2C.TYPE, (payload, context) -> chat("MapDraw : " + payload.text()));
	}

	public static void chat(String text) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(text));
		}
	}

	private static boolean connected() {
		return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(CellsC2S.TYPE);
	}

	@Override
	public void sendMeta(LayerMeta meta) {
		if (connected()) {
			ClientPlayNetworking.send(new LayerMetaC2S(meta));
		}
	}

	@Override
	public void sendDelete(UUID layer) {
		if (connected()) {
			ClientPlayNetworking.send(new LayerDeleteC2S(layer));
		}
	}

	@Override
	public void sendCells(UUID layer, long[] keys, int[] colors) {
		if (connected()) {
			ClientPlayNetworking.send(new CellsC2S(layer, keys, colors));
		}
	}

	@Override
	public void sendZone(UUID layer, Zone zone) {
		if (connected() && ClientPlayNetworking.canSend(ZoneC2S.TYPE)) {
			ClientPlayNetworking.send(new ZoneC2S(layer, zone));
		}
	}

	@Override
	public void sendZoneDelete(UUID layer, UUID zone) {
		if (connected() && ClientPlayNetworking.canSend(ZoneDeleteC2S.TYPE)) {
			ClientPlayNetworking.send(new ZoneDeleteC2S(layer, zone));
		}
	}

	@Override
	public int maxCellsPerPacket() {
		return MapDrawPayloads.MAX_CELLS_C2S;
	}
}
