/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.mount.iso;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.palantir.isofilereader.isofilereader.GenericInternalIsoFile;
import com.palantir.isofilereader.isofilereader.IsoFileReader;
import com.palantir.isofilereader.isofilereader.iso.IsoFormatInternalDataFile;
import com.palantir.isofilereader.isofilereader.iso.types.IsoFormatConstant;
import com.palantir.isofilereader.isofilereader.udf.UdfFormatException;
import com.palantir.isofilereader.isofilereader.udf.UdfInternalDataFile;
import com.palantir.isofilereader.isofilereader.udf.types.files.FileEntry;

/**
 * Read-only adapter over the ISO reader. It indexes metadata once and opens an
 * independent bounded file handle for every payload stream.
 */
final class IsoFileSystem implements AutoCloseable {

	/**
	 * ISO 9660 caps directory nesting at 8 and Rock Ridge relocates anything
	 * deeper, so this bound is generous for real images while keeping the
	 * depth-first index and the recursive extraction walk far from the stack.
	 */
	private static final int MAX_TREE_DEPTH = 64;
	private static final int MAX_ENTRIES = 1_000_000;

	private static final Logger LOG = LoggerFactory.getLogger(IsoFileSystem.class);

	private final Path image;
	private final IsoFileReader reader;
	private final Map<String, IsoEntry> entries = new LinkedHashMap<>();
	private final Set<TrackedInputStream> openStreams = Collections.newSetFromMap(new IdentityHashMap<>());
	private final AtomicBoolean closed = new AtomicBoolean();
	private final IsoEntry root;
	private final String format;
	private int skipped;

	IsoFileSystem(Path image) throws IOException {
		this.image = image.toAbsolutePath().normalize();
		if (!Files.isRegularFile(this.image) || !Files.isReadable(this.image)) {
			throw new FileNotFoundException("The ISO image is not an accessible file.");
		}

		IsoFileReader opened = null;
		try {
			// IsoFileReader's UDF probe checks sector N-256. For a valid but very
			// small ISO 9660 image that position is negative, so bypass UDF probing
			// and still run its normal ISO/Joliet/Rock Ridge table selection.
			if (Files.size(this.image) < 257L * IsoFormatConstant.BYTES_PER_SECTOR) {
				opened = new IsoFileReader(this.image.toFile(), "0,0,0");
				opened.setUdfModeInUse(false);
				opened.findOptimalSettings();
			} else {
				opened = new IsoFileReader(this.image.toFile());
			}
			opened.useSeparatorChar('/');
			GenericInternalIsoFile[] roots = opened.getAllFiles();
			if (roots == null || roots.length == 0) {
				throw new IOException("No supported ISO 9660 or UDF filesystem was found.");
			}

			this.reader = opened;
			this.format = opened.isUdfModeInUse() ? "UDF" : "ISO 9660";
			var seen = Collections.newSetFromMap(new IdentityHashMap<GenericInternalIsoFile, Boolean>());
			int[] count = { 0 };

			if (roots.length == 1 && roots[0] != null && roots[0].isDirectory()
					&& safeName(roots[0]).isBlank()) {
				List<IsoEntry> children = buildChildren(roots[0], "/", seen, count, 0);
				this.root = IsoEntry.root(roots[0], children);
			} else {
				List<IsoEntry> children = new ArrayList<>();
				for (GenericInternalIsoFile item : roots) {
					IsoEntry built = item != null ? buildSafely(item, "/", seen, count, 0) : null;
					if (built != null) {
						children.add(built);
					}
				}
				this.root = IsoEntry.root(null, children);
			}
			entries.put("/", root);
			if (skipped > 0) {
				LOG.warn("Skipped {} malformed entries while indexing {}", skipped, this.image);
			}
		} catch (IOException e) {
			if (opened != null) {
				opened.close();
			}
			throw e;
		} catch (UdfFormatException e) {
			if (opened != null) {
				opened.close();
			}
			throw new IOException("The UDF filesystem is unsupported or damaged.", e);
		} catch (RuntimeException e) {
			if (opened != null) {
				opened.close();
			}
			throw new IOException("The ISO filesystem is unsupported or damaged.", e);
		}
	}

	Path image() {
		return image;
	}

	IsoEntry root() {
		return root;
	}

	String format() {
		return format;
	}

	/** Number of directory entries dropped during indexing because they were malformed. */
	int skippedEntries() {
		return skipped;
	}

	IsoEntry resolve(String path) {
		return entries.get(normalize(path));
	}

	IsoEntry parent(IsoEntry entry) {
		if (entry == null || "/".equals(entry.path())) {
			return null;
		}
		int slash = entry.path().lastIndexOf('/');
		return entries.get(slash <= 0 ? "/" : entry.path().substring(0, slash));
	}

	List<IsoEntry> list(IsoEntry directory) throws IOException {
		ensureOpen();
		if (directory == null || !directory.directory()) {
			throw new IOException("The requested ISO path is not a directory.");
		}
		return directory.children();
	}

	InputStream open(IsoEntry entry) throws IOException {
		ensureOpen();
		if (entry == null || entry.directory() || entry.source() == null) {
			throw new IOException("The requested ISO path is not a file.");
		}
		if (!entry.readable()) {
			throw new IOException("This image stores " + entry.name()
					+ " in a layout the reader cannot follow (embedded, interleaved, or multi-extent data),"
					+ " so its contents cannot be read.");
		}
		if (entry.size() == 0L) {
			return InputStream.nullInputStream();
		}

		long start;
		try {
			start = Math.multiplyExact(entry.source().getLogicalSectorLocation(),
					(long) IsoFormatConstant.BYTES_PER_SECTOR);
		} catch (ArithmeticException e) {
			throw new IOException("The ISO file extent is outside the supported range.", e);
		}
		long imageLength = Files.size(image);
		if (start < 0 || entry.size() < 0 || start > imageLength || entry.size() > imageLength - start) {
			throw new EOFException("The ISO file extent lies outside the image.");
		}

		RandomAccessFile file = new RandomAccessFile(image.toFile(), "r");
		try {
			file.seek(start);
			var stream = new TrackedInputStream(file, entry.size());
			synchronized (openStreams) {
				ensureOpen();
				openStreams.add(stream);
			}
			return stream;
		} catch (IOException | RuntimeException e) {
			file.close();
			throw e;
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		List<TrackedInputStream> streams;
		synchronized (openStreams) {
			streams = List.copyOf(openStreams);
			openStreams.clear();
		}
		for (TrackedInputStream stream : streams) {
			try {
				stream.close();
			} catch (IOException ignored) {
				// Best effort during panel shutdown.
			}
		}
		reader.close();
	}

	boolean isClosed() {
		return closed.get();
	}

	private List<IsoEntry> buildChildren(GenericInternalIsoFile parent, String parentPath,
			Set<GenericInternalIsoFile> seen, int[] count, int depth) throws IOException {
		List<IsoEntry> children = new ArrayList<>();
		GenericInternalIsoFile[] sourceChildren;
		try {
			sourceChildren = parent.getChildren();
		} catch (RuntimeException e) {
			skip(parentPath, safeName(parent), "the directory entries could not be decoded");
			return children;
		}
		if (sourceChildren != null) {
			for (GenericInternalIsoFile child : sourceChildren) {
				IsoEntry built = child != null
						? buildSafely(child, parentPath, seen, count, depth + 1)
						: null;
				if (built != null) {
					children.add(built);
				}
			}
		}
		return children;
	}

	private IsoEntry buildSafely(GenericInternalIsoFile source, String parentPath,
			Set<GenericInternalIsoFile> seen, int[] count, int depth) throws IOException {
		try {
			if (isDirectoryMarker(source)) {
				return null;
			}
			return build(source, parentPath, seen, count, depth);
		} catch (RuntimeException e) {
			seen.remove(source);
			return skip(parentPath, safeName(source), "the entry metadata could not be decoded");
		}
	}

	/**
	 * Index one directory entry, or return {@code null} to drop it.
	 *
	 * <p>A single malformed record must not cost the user the whole image, so a
	 * name that cannot be represented safely, a duplicate path, a cycle, or a
	 * subtree nested deeper than
	 * {@link #MAX_TREE_DEPTH} drops that entry alone and is counted in
	 * {@link #skippedEntries()}. ISO 9660 version suffixes are retained only when
	 * stripping them would collide with an earlier version. The entry limit stops
	 * further indexing instead of making the otherwise browsable image fail.
	 */
	private IsoEntry build(GenericInternalIsoFile source, String parentPath,
			Set<GenericInternalIsoFile> seen, int[] count, int depth) throws IOException {
		if (++count[0] > MAX_ENTRIES) {
			if (count[0] == MAX_ENTRIES + 1) {
				skip(parentPath, safeName(source), "the directory tree exceeds the indexing limit");
			}
			return null;
		}
		String rawName = safeName(source);
		String name = displayName(source);
		if (depth > MAX_TREE_DEPTH) {
			return skip(parentPath, name, "nested deeper than " + MAX_TREE_DEPTH + " levels");
		}
		if (!isSafeName(name)) {
			return skip(parentPath, name, "the entry name is unsafe or empty");
		}
		if (!seen.add(source)) {
			return skip(parentPath, name, "the entry closes a directory cycle");
		}

		String path = childPath(parentPath, name);
		if (entries.containsKey(path) && !rawName.equals(name) && isSafeName(rawName)) {
			String versionedPath = childPath(parentPath, rawName);
			if (!entries.containsKey(versionedPath)) {
				name = rawName;
				path = versionedPath;
			}
		}
		if (entries.containsKey(path)) {
			seen.remove(source);
			return skip(parentPath, name, "the path is already taken by another entry");
		}
		List<IsoEntry> children;
		try {
			children = source.isDirectory()
					? buildChildren(source, path, seen, count, depth)
					: List.of();
		} finally {
			seen.remove(source);
		}

		long size = source.isDirectory() ? 0L : payload(source);
		if (size < 0) {
			return skip(parentPath, name, "the entry declares a negative size");
		}
		IsoEntry entry = new IsoEntry(path, name, source.isDirectory(), size,
				source.isDirectory() || isStreamable(source), modified(source), source, children);
		if (entries.putIfAbsent(path, entry) != null) {
			return skip(parentPath, name, "the path is already taken by another entry");
		}
		return entry;
	}

	private static String childPath(String parentPath, String name) {
		return "/".equals(parentPath) ? "/" + name : parentPath + "/" + name;
	}

	private IsoEntry skip(String parentPath, String name, String reason) {
		skipped++;
		LOG.warn("Skipping ISO entry {} under {} in {}: {}", name, parentPath, image, reason);
		return null;
	}

	private static String safeName(GenericInternalIsoFile source) {
		try {
			return source.getFileName() == null ? "" : source.getFileName();
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static boolean isDirectoryMarker(GenericInternalIsoFile source) {
		return source instanceof IsoFormatInternalDataFile iso
				&& iso.getUnderlyingRecord().isPresent()
				&& iso.getUnderlyingRecord().get().isTopLevelIdentifier();
	}

	private static String displayName(GenericInternalIsoFile source) {
		String name = safeName(source);
		if (!source.isDirectory()) {
			int semicolon = name.lastIndexOf(';');
			if (semicolon > 0 && semicolon < name.length() - 1
					&& name.substring(semicolon + 1).chars().allMatch(Character::isDigit)) {
				name = name.substring(0, semicolon);
			}
		}
		return name;
	}

	private static boolean isSafeName(String name) {
		return !name.isBlank() && !".".equals(name) && !"..".equals(name)
				&& name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
	}

	/**
	 * Size of the file payload in bytes.
	 *
	 * <p>For UDF the allocation descriptor length is the allocated extent, rounded
	 * up to the logical block size; the information length in the file entry is
	 * the real end of the data, so that is what the panel shows and what
	 * extraction copies.
	 */
	static long payload(GenericInternalIsoFile source) {
		if (source instanceof UdfInternalDataFile udf) {
			return udf.getThisFileEntry().getInfoLengthAsLong();
		}
		return source.getSize();
	}

	/**
	 * Whether the payload really is the one contiguous run of sectors that
	 * {@link #open} streams.
	 *
	 * <p>The upstream reader looks at the first allocation descriptor only, so a
	 * UDF file whose data is embedded in its ICB has no sector location at all,
	 * and one spread over several descriptors — unavoidable past the ~1 GiB a
	 * single descriptor's 30-bit length can address — continues somewhere the
	 * reader never reports. Streaming {@link #payload} bytes from the first extent
	 * in either case yields unrelated data that no bounds check would catch,
	 * because the run still lies inside the image. Mark those entries instead, and
	 * let {@link #open} refuse them with an explanation.
	 */
	static boolean isStreamable(GenericInternalIsoFile source) {
		if (source instanceof IsoFormatInternalDataFile iso
				&& iso.getUnderlyingRecord().isPresent()) {
			var record = iso.getUnderlyingRecord().get();
			return (record.getFileFlagsAsInt() & 0x80) == 0
					&& record.getFileUnitSize() == 0
					&& record.getInterLeaveGapSize() == 0;
		}
		if (!(source instanceof UdfInternalDataFile udf)) {
			return true;
		}
		try {
			FileEntry file = udf.getThisFileEntry();
			byte[] flags = file.getIcbTag().getFlags();
			// ECMA-167 4/14.6.8: bits 0-2 of the ICB flags select the allocation
			// descriptor type. The reader computes its sector from the first short_ad,
			// so embedded, long_ad, and ext_ad layouts must not use that offset.
			if (flags == null || flags.length == 0 || (flags[0] & 0x07) != 0) {
				return false;
			}
			long info = file.getInfoLengthAsLong();
			if (info < 0L) {
				return false;
			}
			if (info == 0L) {
				return true;
			}
			byte[] descriptors = file.getAllocationDescriptors();
			if (descriptors == null || descriptors.length < 8) {
				return false;
			}
			int encodedLength = ByteBuffer.wrap(descriptors, 0, Integer.BYTES)
					.order(ByteOrder.LITTLE_ENDIAN).getInt();
			int extentType = encodedLength >>> 30;
			long extentLength = Integer.toUnsignedLong(encodedLength & 0x3fff_ffff);
			// An extent type other than zero is unrecorded or redirects to more ADs.
			// A shorter first extent means the file continues in another descriptor.
			return extentType == 0 && info <= extentLength;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static LocalDateTime modified(GenericInternalIsoFile source) {
		try {
			return source.getDateAsDate().map(Date::toInstant)
					.map(instant -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))
					.orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String normalize(String path) {
		if (path == null || path.isBlank() || "/".equals(path)) {
			return "/";
		}
		String value = path.replace('\\', '/');
		if (!value.startsWith("/")) {
			value = "/" + value;
		}
		while (value.length() > 1 && value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private void ensureOpen() throws IOException {
		if (closed.get()) {
			throw new IOException("The ISO panel has been closed.");
		}
	}

	private final class TrackedInputStream extends InputStream {
		private final RandomAccessFile file;
		private long remaining;
		private boolean streamClosed;

		TrackedInputStream(RandomAccessFile file, long length) {
			this.file = file;
			this.remaining = length;
		}

		@Override
		public int read() throws IOException {
			if (remaining == 0) {
				return -1;
			}
			int value = file.read();
			if (value < 0) {
				throw new EOFException("The ISO image ended before the file was read completely.");
			}
			remaining--;
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
			if (length == 0) {
				return 0;
			}
			if (remaining == 0) {
				return -1;
			}
			int requested = (int) Math.min(length, remaining);
			int read = file.read(buffer, offset, requested);
			if (read < 0) {
				throw new EOFException("The ISO image ended before the file was read completely.");
			}
			remaining -= read;
			return read;
		}

		@Override
		public long skip(long count) throws IOException {
			if (count <= 0) {
				return 0L;
			}
			long step = Math.min(count, remaining);
			if (step == 0L) {
				return 0L;
			}
			file.seek(file.getFilePointer() + step);
			remaining -= step;
			return step;
		}

		/**
		 * Bytes still inside this file's extent. Quick-view plugins size their
		 * first read off this, so reporting the inherited zero sends them down a
		 * byte-at-a-time path.
		 */
		@Override
		public int available() {
			return streamClosed ? 0 : (int) Math.min(remaining, Integer.MAX_VALUE);
		}

		@Override
		public void close() throws IOException {
			if (streamClosed) {
				return;
			}
			streamClosed = true;
			synchronized (openStreams) {
				openStreams.remove(this);
			}
			file.close();
		}
	}
}
