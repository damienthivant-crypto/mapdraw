package fr.thivant.mapdraw.core;

import java.util.Arrays;

/**
 * Table de hachage long -> int sans objets intermédiaires (adressage ouvert, sondage linéaire).
 * Environ 12 à 20 octets par entrée, contre ~80 pour un HashMap&lt;Long, Integer&gt; : indispensable pour
 * les grandes surfaces (centaines de milliers de blocs).
 */
public final class LongIntMap {
	/** Valeur renvoyée quand la clé est absente. */
	public static final int NONE = Integer.MIN_VALUE;
	private static final long EMPTY = Long.MIN_VALUE;

	private long[] keys;
	private int[] values;
	private int size;
	private int mask;
	private int resizeAt;

	public LongIntMap() {
		this(16);
	}

	public LongIntMap(int expected) {
		int cap = Integer.highestOneBit(Math.max(4, (int) (expected / 0.6f) + 1) - 1) << 1;
		allocate(cap);
	}

	private void allocate(int cap) {
		keys = new long[cap];
		values = new int[cap];
		Arrays.fill(keys, EMPTY);
		mask = cap - 1;
		resizeAt = (int) (cap * 0.6f);
	}

	private static int hash(long k) {
		long h = k * 0x9E3779B97F4A7C15L;
		h ^= h >>> 32;
		h ^= h >>> 16;
		return (int) h;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public boolean containsKey(long key) {
		return get(key) != NONE;
	}

	public int get(long key) {
		int i = hash(key) & mask;

		while (true) {
			long k = keys[i];

			if (k == EMPTY) {
				return NONE;
			}

			if (k == key) {
				return values[i];
			}

			i = (i + 1) & mask;
		}
	}

	/** @return l'ancienne valeur ou NONE */
	public int put(long key, int value) {
		if (key == EMPTY) {
			throw new IllegalArgumentException("clé réservée");
		}

		int i = hash(key) & mask;

		while (true) {
			long k = keys[i];

			if (k == EMPTY) {
				keys[i] = key;
				values[i] = value;

				if (++size > resizeAt) {
					rehash(keys.length << 1);
				}

				return NONE;
			}

			if (k == key) {
				int old = values[i];
				values[i] = value;
				return old;
			}

			i = (i + 1) & mask;
		}
	}

	/** @return l'ancienne valeur ou NONE */
	public int remove(long key) {
		int i = hash(key) & mask;

		while (true) {
			long k = keys[i];

			if (k == EMPTY) {
				return NONE;
			}

			if (k == key) {
				int old = values[i];
				shiftDown(i);
				size--;
				return old;
			}

			i = (i + 1) & mask;
		}
	}

	/** Suppression par décalage arrière (pas de « pierre tombale »). */
	private void shiftDown(int pos) {
		int last = pos;

		while (true) {
			pos = (last + 1) & mask;

			while (true) {
				long k = keys[pos];

				if (k == EMPTY) {
					keys[last] = EMPTY;
					return;
				}

				int slot = hash(k) & mask;

				if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) {
					break;
				}

				pos = (pos + 1) & mask;
			}

			keys[last] = keys[pos];
			values[last] = values[pos];
			last = pos;
		}
	}

	private void rehash(int newCap) {
		long[] oldKeys = keys;
		int[] oldValues = values;
		allocate(newCap);
		size = 0;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY) {
				put(oldKeys[i], oldValues[i]);
			}
		}
	}

	public void clear() {
		Arrays.fill(keys, EMPTY);
		size = 0;
	}

	public interface Visitor {
		void visit(long key, int value);
	}

	public void forEach(Visitor visitor) {
		long[] k = keys;
		int[] v = values;

		for (int i = 0; i < k.length; i++) {
			if (k[i] != EMPTY) {
				visitor.visit(k[i], v[i]);
			}
		}
	}

	public long[] keysArray() {
		long[] out = new long[size];
		int n = 0;

		for (long k : keys) {
			if (k != EMPTY) {
				out[n++] = k;
			}
		}

		return out;
	}
}
