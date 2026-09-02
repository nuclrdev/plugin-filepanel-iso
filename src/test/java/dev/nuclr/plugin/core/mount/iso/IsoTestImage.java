package dev.nuclr.plugin.core.mount.iso;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Creates a tiny standards-shaped ISO 9660 image without an external tool. */
final class IsoTestImage {

	static final int SECTOR = 2_048;
	static final int LARGE_LENGTH = 5 * 64 * 1_024 + 37;
	static final byte[] ROOT_TEXT = "root file\n".getBytes(StandardCharsets.UTF_8);
	static final byte[] NESTED_TEXT = "nested content".getBytes(StandardCharsets.UTF_8);

	private IsoTestImage() {
	}

	static Path create(Path image) throws IOException {
		return create(image, false);
	}

	static Path createCompact(Path image) throws IOException {
		return create(image, true);
	}

	static Path createWithVersions(Path image) throws IOException {
		return create(image, false, true);
	}

	static Path createWithUnsupportedMultiExtentFile(Path image) throws IOException {
		create(image);
		try (var file = new RandomAccessFile(image.toFile(), "rw")) {
			byte[] directory = new byte[SECTOR];
			file.seek(20L * SECTOR);
			file.readFully(directory);
			for (int offset = 0; offset < directory.length && directory[offset] != 0;
					offset += Byte.toUnsignedInt(directory[offset])) {
				int nameLength = Byte.toUnsignedInt(directory[offset + 32]);
				String name = new String(directory, offset + 33, nameLength, StandardCharsets.US_ASCII);
				if ("LARGE.BIN;1".equals(name)) {
					directory[offset + 25] |= (byte) 0x80;
					file.seek(20L * SECTOR);
					file.write(directory);
					return image;
				}
			}
		}
		throw new IOException("The test ISO did not contain LARGE.BIN;1.");
	}

	private static Path create(Path image, boolean compact) throws IOException {
		return create(image, compact, false);
	}

	private static Path create(Path image, boolean compact, boolean versions) throws IOException {
		int largeSectors = (LARGE_LENGTH + SECTOR - 1) / SECTOR;
		int largeSector = versions ? 26 : 24;
		// The UDF probe checks N-256 as required by the standard. Keep the tiny
		// fixture large enough for that location to be non-negative.
		int totalSectors = compact ? largeSector + largeSectors : Math.max(600, largeSector + largeSectors);
		try (var file = new RandomAccessFile(image.toFile(), "rw")) {
			file.setLength((long) totalSectors * SECTOR);
			writePvd(file, totalSectors);
			writeTerminator(file);
			writeDirectories(file, largeSector, versions);
			writeAt(file, 22, ROOT_TEXT);
			writeAt(file, 23, NESTED_TEXT);
			if (versions) {
				writeAt(file, 24, "version one".getBytes(StandardCharsets.UTF_8));
				writeAt(file, 25, "version two".getBytes(StandardCharsets.UTF_8));
			}
			file.seek((long) largeSector * SECTOR);
			byte[] chunk = new byte[8_192];
			int position = 0;
			while (position < LARGE_LENGTH) {
				int count = Math.min(chunk.length, LARGE_LENGTH - position);
				for (int i = 0; i < count; i++) {
					chunk[i] = expectedLargeByte(position + i);
				}
				file.write(chunk, 0, count);
				position += count;
			}
		}
		return image;
	}

	static byte expectedLargeByte(int offset) {
		return (byte) (offset % 251);
	}

	private static void writePvd(RandomAccessFile file, int sectors) throws IOException {
		byte[] pvd = new byte[SECTOR];
		pvd[0] = 1;
		putAscii(pvd, 1, "CD001");
		pvd[6] = 1;
		putPaddedAscii(pvd, 8, 32, "NUCLR");
		putPaddedAscii(pvd, 40, 32, "NUCLR_TEST_ISO");
		putBothEndian32(pvd, 80, sectors);
		putBothEndian16(pvd, 120, 1);
		putBothEndian16(pvd, 124, 1);
		putBothEndian16(pvd, 128, SECTOR);
		putBothEndian32(pvd, 132, 10);
		putLittleEndian32(pvd, 140, 18);
		putBigEndian32(pvd, 148, 19);
		byte[] root = record(20, SECTOR, true, new byte[] { 0 });
		System.arraycopy(root, 0, pvd, 156, root.length);
		pvd[881] = 1;
		writeAt(file, 16, pvd);
	}

	private static void writeTerminator(RandomAccessFile file) throws IOException {
		byte[] terminator = new byte[SECTOR];
		terminator[0] = (byte) 0xff;
		putAscii(terminator, 1, "CD001");
		terminator[6] = 1;
		writeAt(file, 17, terminator);
	}

	private static void writeDirectories(RandomAccessFile file, int largeSector, boolean versions) throws IOException {
		byte[] root = new byte[SECTOR];
		int at = 0;
		at = append(root, at, record(20, SECTOR, true, new byte[] { 0 }));
		at = append(root, at, record(20, SECTOR, true, new byte[] { 1 }));
		at = append(root, at, record(21, SECTOR, true, "FOLDER".getBytes(StandardCharsets.US_ASCII)));
		at = append(root, at, record(22, ROOT_TEXT.length, false,
				"README.TXT;1".getBytes(StandardCharsets.US_ASCII)));
		if (versions) {
			at = append(root, at, record(24, "version one".length(), false,
					"VERSION.TXT;1".getBytes(StandardCharsets.US_ASCII)));
			at = append(root, at, record(25, "version two".length(), false,
					"VERSION.TXT;2".getBytes(StandardCharsets.US_ASCII)));
		}
		append(root, at, record(largeSector, LARGE_LENGTH, false,
				"LARGE.BIN;1".getBytes(StandardCharsets.US_ASCII)));
		writeAt(file, 20, root);

		byte[] nested = new byte[SECTOR];
		at = 0;
		at = append(nested, at, record(21, SECTOR, true, new byte[] { 0 }));
		at = append(nested, at, record(20, SECTOR, true, new byte[] { 1 }));
		append(nested, at, record(23, NESTED_TEXT.length, false,
				"NESTED.TXT;1".getBytes(StandardCharsets.US_ASCII)));
		writeAt(file, 21, nested);
	}

	private static byte[] record(int extent, int size, boolean directory, byte[] name) {
		int length = 33 + name.length + (name.length % 2 == 0 ? 1 : 0);
		byte[] record = new byte[length];
		record[0] = (byte) length;
		putBothEndian32(record, 2, extent);
		putBothEndian32(record, 10, size);
		record[18] = (byte) (2026 - 1900);
		record[19] = 9;
		record[20] = 1;
		record[21] = 10;
		record[22] = 30;
		record[23] = 15;
		record[24] = 0;
		record[25] = directory ? (byte) 2 : 0;
		putBothEndian16(record, 28, 1);
		record[32] = (byte) name.length;
		System.arraycopy(name, 0, record, 33, name.length);
		return record;
	}

	private static int append(byte[] target, int offset, byte[] value) {
		System.arraycopy(value, 0, target, offset, value.length);
		return offset + value.length;
	}

	private static void writeAt(RandomAccessFile file, int sector, byte[] data) throws IOException {
		file.seek((long) sector * SECTOR);
		file.write(data);
	}

	private static void putAscii(byte[] target, int offset, String text) {
		byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(bytes, 0, target, offset, bytes.length);
	}

	private static void putPaddedAscii(byte[] target, int offset, int length, String text) {
		java.util.Arrays.fill(target, offset, offset + length, (byte) ' ');
		putAscii(target, offset, text);
	}

	private static void putBothEndian16(byte[] target, int offset, int value) {
		target[offset] = (byte) value;
		target[offset + 1] = (byte) (value >>> 8);
		target[offset + 2] = (byte) (value >>> 8);
		target[offset + 3] = (byte) value;
	}

	private static void putBothEndian32(byte[] target, int offset, long value) {
		putLittleEndian32(target, offset, value);
		putBigEndian32(target, offset + 4, value);
	}

	private static void putLittleEndian32(byte[] target, int offset, long value) {
		for (int i = 0; i < 4; i++) {
			target[offset + i] = (byte) (value >>> (8 * i));
		}
	}

	private static void putBigEndian32(byte[] target, int offset, long value) {
		for (int i = 0; i < 4; i++) {
			target[offset + i] = (byte) (value >>> (8 * (3 - i)));
		}
	}
}
