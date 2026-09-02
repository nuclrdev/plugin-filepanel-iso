package dev.nuclr.plugin.core.mount.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.palantir.isofilereader.isofilereader.udf.UdfInternalDataFile;
import com.palantir.isofilereader.isofilereader.udf.types.files.FileEntry;

class UdfStreamabilityTest {

	@Test
	void acceptsOnlyARecordedContiguousShortAllocationDescriptor() {
		UdfInternalDataFile contiguous = udfFile(10L, 10, 0, 0);
		assertEquals(10L, IsoFileSystem.payload(contiguous));
		assertTrue(IsoFileSystem.isStreamable(contiguous));

		assertFalse(IsoFileSystem.isStreamable(udfFile(11L, 10, 0, 0)),
				"information continuing beyond the first extent is fragmented");
		assertFalse(IsoFileSystem.isStreamable(udfFile(10L, 10, 3, 0)),
				"embedded ICB data has no sector extent");
		assertFalse(IsoFileSystem.isStreamable(udfFile(10L, 10, 1, 0)),
				"the upstream offset calculation cannot safely follow long_ad");
		assertFalse(IsoFileSystem.isStreamable(udfFile(10L, 10, 0, 3)),
				"a continuation allocation descriptor is not recorded file data");
	}

	@Test
	void permitsAnEmptyShortDescriptorFileWithoutReadingASector() {
		assertTrue(IsoFileSystem.isStreamable(udfFile(0L, 0, 0, 0)));
	}

	private static UdfInternalDataFile udfFile(long informationLength, int extentLength,
			int allocationType, int extentType) {
		byte[] record = new byte[184];
		record[27] = FileEntry.FILE_AS_RAN_ACCESS_STREAM;
		record[34] = (byte) allocationType;
		putLong(record, 56, informationLength);
		putInt(record, 172, 8);
		putInt(record, 176, extentLength | extentType << 30);
		putInt(record, 180, 20);
		return new UdfInternalDataFile(new FileEntry(record), null, 0L);
	}

	private static void putInt(byte[] target, int offset, int value) {
		for (int index = 0; index < Integer.BYTES; index++) {
			target[offset + index] = (byte) (value >>> index * 8);
		}
	}

	private static void putLong(byte[] target, int offset, long value) {
		for (int index = 0; index < Long.BYTES; index++) {
			target[offset + index] = (byte) (value >>> index * 8);
		}
	}
}
