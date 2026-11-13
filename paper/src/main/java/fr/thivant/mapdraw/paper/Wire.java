package fr.thivant.mapdraw.paper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import fr.thivant.mapdraw.core.LayerMeta;

/**
 * Encodage binaire identique à celui du mod (FriendlyByteBuf de Minecraft) :
 * VarInt, long/int big-endian, UUID = 2 longs, chaînes = VarInt(longueur UTF-8) + octets, booléen = 1 octet.
 */
final class Wire {
	private Wire() {
	}

	static final class Writer {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

		Writer varInt(int value) {
			while ((value & ~0x7F) != 0) {
				out.write((value & 0x7F) | 0x80);
				value >>>= 7;
			}

			out.write(value);
			return this;
		}

		Writer i32(int v) {
			out.write(v >>> 24);
			out.write(v >>> 16);
			out.write(v >>> 8);
			out.write(v);
			return this;
		}

		Writer i64(long v) {
			i32((int) (v >>> 32));
			i32((int) v);
			return this;
		}

		Writer bool(boolean b) {
			out.write(b ? 1 : 0);
			return this;
		}

		Writer uuid(UUID id) {
			i64(id.getMostSignificantBits());
			i64(id.getLeastSignificantBits());
			return this;
		}

		Writer utf(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			varInt(bytes.length);
			out.write(bytes, 0, bytes.length);
			return this;
		}

		Writer meta(LayerMeta m) {
			uuid(m.id());
			utf(m.name());
			uuid(m.owner());
			utf(m.ownerName());
			utf(m.dimension());
			bool(m.shared());
			return this;
		}

		byte[] bytes() {
			return out.toByteArray();
		}
	}

	static final class Reader {
		private final ByteBuffer buf;

		Reader(byte[] data) {
			this.buf = ByteBuffer.wrap(data);
		}

		int varInt() {
			int value = 0;
			int shift = 0;
			byte b;

			do {
				if (shift >= 35) {
					throw new IllegalArgumentException("VarInt trop long");
				}

				b = buf.get();
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);

			return value;
		}

		int i32() {
			return buf.getInt();
		}

		long i64() {
			return buf.getLong();
		}

		boolean bool() {
			return buf.get() != 0;
		}

		UUID uuid() {
			return new UUID(buf.getLong(), buf.getLong());
		}

		String utf(int maxChars) {
			int len = varInt();

			if (len < 0 || len > maxChars * 4) {
				throw new IllegalArgumentException("Chaîne trop longue");
			}

			byte[] bytes = new byte[len];
			buf.get(bytes);
			return new String(bytes, StandardCharsets.UTF_8);
		}

		LayerMeta meta() {
			UUID id = uuid();
			String name = utf(128);
			UUID owner = uuid();
			String ownerName = utf(64);
			String dimension = utf(256);
			boolean shared = bool();
			return new LayerMeta(id, name, owner, ownerName, dimension, shared);
		}
	}
}
