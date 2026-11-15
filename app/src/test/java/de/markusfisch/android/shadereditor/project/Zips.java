package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A zip built entry by entry as bytes, for archives no well-meaning
 * tool writes: any name, the same name twice, a link, a wrong
 * checksum, an end that lists other entries than there are.
 */
final class Zips {
	/** The type bits of a symbolic link, as Unix tools write them. */
	static final long LINK = 0120777L << 16;

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final List<byte[]> central = new ArrayList<>();

	Zips add(String name, String text) {
		return add(name.getBytes(StandardCharsets.UTF_8),
				text.getBytes(StandardCharsets.UTF_8), 0x800, 0, -1);
	}

	/**
	 * @param flags      the general purpose bits; 0x800 says UTF-8
	 * @param attributes the external attributes
	 * @param crc        the checksum to claim, -1 for the true one
	 */
	Zips add(byte[] name, byte[] data, int flags, long attributes,
			long crc) {
		if (crc < 0) {
			CRC32 sum = new CRC32();
			sum.update(data);
			crc = sum.getValue();
		}
		int offset = out.size();
		ByteBuffer local = buffer(30).putInt(0x04034b50).putShort((short) 20);
		fields(local, name, data, flags, crc).putShort((short) 0);
		write(local.array(), name, data);
		ByteBuffer listed = buffer(46).putInt(0x02014b50)
				.putShort((short) (3 << 8 | 20)).putShort((short) 20);
		fields(listed, name, data, flags, crc).putShort((short) 0)
				.putShort((short) 0).putShort((short) 0).putShort((short) 0)
				.putInt((int) attributes).putInt(offset);
		ByteArrayOutputStream entry = new ByteArrayOutputStream();
		entry.writeBytes(listed.array());
		entry.writeBytes(name);
		central.add(entry.toByteArray());
		return this;
	}

	/** Return the archive, its end listing its entries. */
	byte[] end() {
		return end(central.size());
	}

	/** Return the archive with an end that lists count entries. */
	byte[] end(int count) {
		ByteArrayOutputStream archive = new ByteArrayOutputStream();
		archive.writeBytes(out.toByteArray());
		int size = 0;
		for (byte[] entry : central) {
			archive.writeBytes(entry);
			size += entry.length;
		}
		archive.writeBytes(buffer(22).putInt(0x06054b50).putShort((short) 0)
				.putShort((short) 0).putShort((short) count)
				.putShort((short) count).putInt(size).putInt(out.size())
				.putShort((short) 0).array());
		return archive.toByteArray();
	}

	/** Return only the entries, as an archive cut before its end. */
	byte[] cut() {
		return out.toByteArray();
	}

	// What the entry's header and its listing both hold.
	private static ByteBuffer fields(ByteBuffer buffer, byte[] name,
			byte[] data, int flags, long crc) {
		return buffer.putShort((short) flags).putShort((short) 0)
				.putShort((short) 0).putShort((short) (46 << 9 | 11 << 5 | 15))
				.putInt((int) crc).putInt(data.length).putInt(data.length)
				.putShort((short) name.length);
	}

	private void write(byte[]... parts) {
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
	}

	private static ByteBuffer buffer(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}
}
