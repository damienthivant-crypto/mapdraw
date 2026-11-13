package fr.thivant.mapdraw.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import fr.thivant.mapdraw.client.gui.ConfigScreen;

/**
 * Bouton « Configurer » dans Mod Menu (chargé uniquement si Mod Menu est installé).
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::new;
	}
}
