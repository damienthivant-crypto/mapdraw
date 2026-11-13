package fr.thivant.mapdraw.client.gui;

import java.lang.reflect.Method;

import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLScancode;

import net.minecraft.client.input.KeyEvent;

/**
 * Raccourcis clavier, indépendants de la disposition (AZERTY/QWERTY) : la touche physique
 * est convertie en caractère via SDL quand c'est possible.
 * Ctrl (ou Cmd sur macOS) + Z = annuler, Ctrl/Cmd + Y ou Ctrl/Cmd + Maj + Z = rétablir.
 */
public final class Keys {
	private static final Method KEY_FROM_SCANCODE = findKeyFromScancode();

	private Keys() {
	}

	private static Method findKeyFromScancode() {
		try {
			Class<?> keyboard = Class.forName("org.lwjgl.sdl.SDLKeyboard");
			return keyboard.getMethod("SDL_GetKeyFromScancode", int.class, short.class, boolean.class);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Caractère (minuscule) produit par la touche, selon la disposition du clavier. */
	private static int keyChar(KeyEvent event) {
		int scancode = event.key();

		if (KEY_FROM_SCANCODE != null) {
			try {
				Object r = KEY_FROM_SCANCODE.invoke(null, scancode, (short) 0, false);

				if (r instanceof Integer code && code > 0 && code < 0x80) {
					return Character.toLowerCase(code);
				}
			} catch (Throwable ignored) {
				// repli sur la touche physique
			}
		}

		if (scancode == SDLScancode.SDL_SCANCODE_Z) {
			return 'z';
		}

		if (scancode == SDLScancode.SDL_SCANCODE_Y) {
			return 'y';
		}

		return 0;
	}

	public static boolean ctrl(KeyEvent event) {
		return (event.modifiers() & (SDLKeycode.SDL_KMOD_CTRL | SDLKeycode.SDL_KMOD_GUI)) != 0;
	}

	public static boolean shift(KeyEvent event) {
		return (event.modifiers() & SDLKeycode.SDL_KMOD_SHIFT) != 0;
	}

	public static boolean isUndo(KeyEvent event) {
		return ctrl(event) && !shift(event) && keyChar(event) == 'z';
	}

	public static boolean isRedo(KeyEvent event) {
		int c = keyChar(event);
		return ctrl(event) && (c == 'y' || (c == 'z' && shift(event)));
	}

	public static boolean isBackspace(KeyEvent event) {
		return event.key() == SDLScancode.SDL_SCANCODE_BACKSPACE;
	}

	public static boolean isEnter(KeyEvent event) {
		return event.key() == SDLScancode.SDL_SCANCODE_RETURN || event.key() == SDLScancode.SDL_SCANCODE_KP_ENTER;
	}

	/** Suppr ou Retour arrière (la touche « delete » des Mac). */
	public static boolean isDelete(KeyEvent event) {
		return event.key() == SDLScancode.SDL_SCANCODE_DELETE || isBackspace(event);
	}

	/** Décalage en X pour les flèches gauche / droite (Maj = 10 blocs), sinon 0. */
	public static int arrowDx(KeyEvent event) {
		int step = shift(event) ? 10 : 1;
		return event.key() == SDLScancode.SDL_SCANCODE_LEFT ? -step : event.key() == SDLScancode.SDL_SCANCODE_RIGHT ? step : 0;
	}

	/** Décalage en Z pour les flèches haut / bas (nord = -Z), sinon 0. */
	public static int arrowDz(KeyEvent event) {
		int step = shift(event) ? 10 : 1;
		return event.key() == SDLScancode.SDL_SCANCODE_UP ? -step : event.key() == SDLScancode.SDL_SCANCODE_DOWN ? step : 0;
	}

	public static boolean isEscape(KeyEvent event) {
		return event.key() == SDLScancode.SDL_SCANCODE_ESCAPE;
	}
}
