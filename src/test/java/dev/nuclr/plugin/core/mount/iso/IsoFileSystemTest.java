package dev.nuclr.plugin.core.mount.iso;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrPluginCallback;

class IsoFileSystemTest {

	@TempDir
	Path tempDir;

	@Test
	void listsRootTraversesDirectoriesAndReadsFiles() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("test.iso"));
		try (var iso = new IsoFileSystem(image)) {
			assertEquals("ISO 9660", iso.format());
			assertEquals(List.of("FOLDER", "README.TXT", "LARGE.BIN"),
					iso.root().children().stream().map(IsoEntry::name).toList());

			IsoEntry folder = iso.resolve("/FOLDER");
			assertTrue(folder.directory());
			assertEquals(iso.root(), iso.parent(folder));
			assertEquals(List.of("NESTED.TXT"), folder.children().stream().map(IsoEntry::name).toList());

			try (var input = iso.open(iso.resolve("/FOLDER/NESTED.TXT"))) {
				assertEquals(IsoTestImage.NESTED_TEXT.length, input.available());
				assertArrayEquals(IsoTestImage.NESTED_TEXT, input.readAllBytes());
				assertEquals(0, input.available());
			}
		}
	}

	@Test
	void extractsFilesDirectoriesAndStreamsAcrossManyBuffers() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("stream.iso"));
		Path output = Files.createDirectory(tempDir.resolve("out"));
		try (var iso = new IsoFileSystem(image)) {
			assertTrue(IsoExtractor.extract(iso,
					List.of(iso.resolve("/README.TXT"), iso.resolve("/FOLDER"), iso.resolve("/LARGE.BIN")),
					output, null));
		}

		assertArrayEquals(IsoTestImage.ROOT_TEXT, Files.readAllBytes(output.resolve("README.TXT")));
		assertArrayEquals(IsoTestImage.NESTED_TEXT,
				Files.readAllBytes(output.resolve("FOLDER/NESTED.TXT")));
		Path large = output.resolve("LARGE.BIN");
		assertEquals(IsoTestImage.LARGE_LENGTH, Files.size(large));
		try (var input = Files.newInputStream(large)) {
			byte[] bytes = input.readAllBytes();
			for (int offset : new int[] { 0, 65_535, 65_536, bytes.length - 1 }) {
				assertEquals(IsoTestImage.expectedLargeByte(offset), bytes[offset]);
			}
		}
	}

	@Test
	void closesOutstandingStreamsAndReleasesTheImage() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("locked.iso"));
		var iso = new IsoFileSystem(image);
		var stream = iso.open(iso.resolve("/LARGE.BIN"));

		iso.close();

		assertTrue(iso.isClosed());
		assertThrows(IOException.class, stream::read);
		Path renamed = tempDir.resolve("renamed.iso");
		Files.move(image, renamed);
		assertTrue(Files.exists(renamed));
	}

	@Test
	void rejectsInvalidImagesGracefully() throws Exception {
		Path invalid = Files.writeString(tempDir.resolve("broken.iso"), "not an iso");
		IOException error = assertThrows(IOException.class, () -> new IsoFileSystem(invalid));
		assertFalse(error.getMessage().isBlank());
	}

	@Test
	void opensAValidIsoSmallerThanTheUdfAnchorArea() throws Exception {
		Path image = IsoTestImage.createCompact(tempDir.resolve("compact.iso"));
		try (var iso = new IsoFileSystem(image)) {
			try (var input = iso.open(iso.resolve("/README.TXT"))) {
				assertEquals("root file\n", new String(input.readAllBytes()));
			}
		}
	}

	@Test
	void retainsIsoVersionSuffixWhenStrippingItWouldCollide() throws Exception {
		Path image = IsoTestImage.createWithVersions(tempDir.resolve("versions.iso"));
		try (var iso = new IsoFileSystem(image)) {
			assertEquals("version one", new String(iso.open(iso.resolve("/VERSION.TXT")).readAllBytes()));
			assertEquals("version two", new String(iso.open(iso.resolve("/VERSION.TXT;2")).readAllBytes()));
		}
	}

	@Test
	void refusesIsoMultiExtentPayloadInsteadOfReturningTruncatedData() throws Exception {
		Path image = IsoTestImage.createWithUnsupportedMultiExtentFile(tempDir.resolve("multi.iso"));
		try (var iso = new IsoFileSystem(image)) {
			IsoEntry entry = iso.resolve("/LARGE.BIN");
			assertFalse(entry.readable());
			assertThrows(IOException.class, () -> iso.open(entry));
		}
	}

	@Test
	void cancellationRemovesThePartialFileAndPreservesAnExistingTarget() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("cancel.iso"));
		Path output = Files.createDirectory(tempDir.resolve("cancel-out"));
		Path target = Files.writeString(output.resolve("LARGE.BIN"), "original");
		var callback = new CancellingCallback();
		try (var iso = new IsoFileSystem(image)) {
			assertThrows(IsoExtractor.ExtractionCancelledException.class,
					() -> IsoExtractor.extract(iso, List.of(iso.resolve("/LARGE.BIN")), output, callback,
							(source, existing) -> IsoExtractor.ConflictAction.OVERWRITE));
		}
		assertEquals("original", Files.readString(target));
		try (var files = Files.list(output)) {
			assertEquals(List.of("LARGE.BIN"), files.map(path -> path.getFileName().toString()).toList());
		}
	}

	@Test
	void destinationConflictsRequireADecisionAndCanKeepBoth() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("conflict.iso"));
		Path output = Files.createDirectory(tempDir.resolve("conflict-out"));
		Path existing = Files.writeString(output.resolve("README.TXT"), "original");
		try (var iso = new IsoFileSystem(image)) {
			IsoEntry readme = iso.resolve("/README.TXT");
			assertThrows(IsoExtractor.ExtractionCancelledException.class,
					() -> IsoExtractor.extract(iso, List.of(readme), output, null));
			assertEquals("original", Files.readString(existing));

			assertTrue(IsoExtractor.extract(iso, List.of(readme), output, null,
					(source, target) -> IsoExtractor.ConflictAction.KEEP_BOTH));
		}
		assertEquals("original", Files.readString(existing));
		assertArrayEquals(IsoTestImage.ROOT_TEXT, Files.readAllBytes(output.resolve("README (2).TXT")));
	}

	private static final class CancellingCallback implements NuclrPluginCallback {
		private volatile boolean cancelled;
		@Override public void onStart(String description) { }
		@Override public void onProgress(long current, long total) { cancelled = current > 0; }
		@Override public void onComplete() { }
		@Override public void onError(String description, Exception e) { }
		@Override public boolean isCancelled() { return cancelled; }
	}
}
