package fr.thivant.mapdraw.client.jm;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

import fr.thivant.mapdraw.MapDraw;

/**
 * Plugin JourneyMap (chargé par JourneyMap via l'entrypoint « journeymap » du fabric.mod.json).
 * Aucune autre classe du mod ne référence ce paquet : sans JourneyMap, il n'est jamais chargé.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class MapDrawJourneyMapPlugin implements IClientPlugin {
	@Override
	public void initialize(IClientAPI jmClientApi) {
		new JourneyMapBridge(jmClientApi).register();
		MapDraw.LOGGER.info("MapDraw : intégration JourneyMap active");
	}

	@Override
	public String getModId() {
		return MapDraw.MOD_ID;
	}
}
