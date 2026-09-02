/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.mount.iso;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import dev.nuclr.platform.plugin.NuclrPluginCallback;

/** Cancellable, streaming extraction from an ISO tree to the local filesystem. */
final class IsoExtractor {

	private static final int BUFFER_SIZE = 64 * 1024;

	private IsoExtractor() {
	}

	enum ConflictAction {
		OVERWRITE, SKIP, KEEP_BOTH, CANCEL
	}

	@FunctionalInterface
	interface ConflictResolver {
		ConflictAction resolve(IsoEntry source, Path target);
	}

	static boolean extract(IsoFileSystem fileSystem, List<IsoEntry> sources, Path destination,
			NuclrPluginCallback callback) throws IOException {
		return extract(fileSystem, sources, destination, callback,
				(source, target) -> ConflictAction.CANCEL);
	}

	static boolean extract(IsoFileSystem fileSystem, List<IsoEntry> sources, Path destination,
			NuclrPluginCallback callback, ConflictResolver conflictResolver) throws IOException {
		if (fileSystem == null || sources == null || sources.isEmpty()) {
			return false;
		}
		if (conflictResolver == null) {
			throw new IllegalArgumentException("A destination conflict resolver is required.");
		}
		Path root = destination != null ? destination.toAbsolutePath().normalize() : null;
		if (root == null || !Files.isDirectory(root)) {
			throw new IOException("The extraction destination is not a local directory.");
		}

		long total = sources.stream().mapToLong(IsoExtractor::totalFileBytes).reduce(0L, IsoExtractor::saturatedAdd);
		long[] copied = { 0L };
		if (callback != null) {
			callback.onStart(sources.size() == 1
					? "Extracting " + sources.get(0).name()
					: "Extracting " + sources.size() + " items");
		}

		for (IsoEntry source : sources) {
			checkCancelled(callback);
			Path target = safeResolve(root, source.name());
			extractEntry(fileSystem, source, root, target, copied, total, callback, conflictResolver);
		}

		if (callback != null) {
			callback.onComplete();
		}
		return true;
	}

	private static void extractEntry(IsoFileSystem fileSystem, IsoEntry source, Path destinationRoot,
			Path target, long[] copied, long total, NuclrPluginCallback callback,
			ConflictResolver conflictResolver) throws IOException {
		checkCancelled(callback);
		ensureInside(destinationRoot, target);
		if (source.directory()) {
			boolean created = false;
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				ConflictResult result = resolveConflict(source, target, conflictResolver);
				if (result.skip()) {
					return;
				}
				target = result.target();
				if (result.overwrite()) {
					deleteExisting(target);
				}
			}
			if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectories(target);
				created = true;
			}
			for (IsoEntry child : source.children()) {
				extractEntry(fileSystem, child, destinationRoot, safeResolve(target, child.name()),
						copied, total, callback, conflictResolver);
			}
			if (created) {
				applyTimestamp(target, source);
			}
			return;
		}

		boolean overwrite = false;
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			ConflictResult result = resolveConflict(source, target, conflictResolver);
			if (result.skip()) {
				return;
			}
			target = result.target();
			overwrite = result.overwrite();
		}

		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path partial = target.resolveSibling(target.getFileName() + ".nuclr-part-" + UUID.randomUUID());
		try {
			try (InputStream input = fileSystem.open(source);
					OutputStream output = Files.newOutputStream(partial,
							StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				byte[] buffer = new byte[BUFFER_SIZE];
				long fileBytes = 0L;
				for (int read; (read = input.read(buffer)) >= 0;) {
					checkCancelled(callback);
					if (read == 0) {
						continue;
					}
					output.write(buffer, 0, read);
					fileBytes += read;
					copied[0] = saturatedAdd(copied[0], read);
					if (callback != null) {
						callback.onProgress(copied[0], total);
					}
				}
				if (fileBytes != source.size()) {
					throw new IOException("The ISO image did not contain all bytes declared for " + source.name() + ".");
				}
			}
			checkCancelled(callback);
			if (overwrite && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				deleteExisting(target);
			}
			moveCompleted(partial, target, overwrite);
			applyTimestamp(target, source);
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	private static ConflictResult resolveConflict(IsoEntry source, Path target,
			ConflictResolver resolver) throws IOException {
		ConflictAction action = resolver.resolve(source, target);
		if (action == null || action == ConflictAction.CANCEL) {
			throw new ExtractionCancelledException();
		}
		return switch (action) {
			case OVERWRITE -> new ConflictResult(target, true, false);
			case SKIP -> new ConflictResult(target, false, true);
			case KEEP_BOTH -> new ConflictResult(availableTarget(target), false, false);
			case CANCEL -> throw new ExtractionCancelledException();
		};
	}

	private static Path availableTarget(Path target) throws IOException {
		String fileName = target.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
		String extension = dot > 0 ? fileName.substring(dot) : "";
		for (int copy = 2; copy <= 10_000; copy++) {
			Path candidate = target.resolveSibling(stem + " (" + copy + ")" + extension);
			if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
				return candidate;
			}
		}
		throw new FileAlreadyExistsException(target.toString(), null,
				"No available keep-both destination name was found");
	}

	private static void deleteExisting(Path target) throws IOException {
		if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			Files.deleteIfExists(target);
			return;
		}
		try (var paths = Files.walk(target)) {
			for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(item);
			}
		}
	}

	private static Path safeResolve(Path parent, String name) throws IOException {
		Path resolved = parent.resolve(name).toAbsolutePath().normalize();
		ensureInside(parent.toAbsolutePath().normalize(), resolved);
		return resolved;
	}

	private static void ensureInside(Path root, Path target) throws IOException {
		if (!target.startsWith(root)) {
			throw new IOException("An ISO entry attempted to escape the extraction directory.");
		}
	}

	private static void moveCompleted(Path source, Path target, boolean overwrite) throws IOException {
		if (!overwrite) {
			Files.move(source, target);
			return;
		}
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private record ConflictResult(Path target, boolean overwrite, boolean skip) {
	}

	private static void applyTimestamp(Path target, IsoEntry source) {
		if (source.modified() == null) {
			return;
		}
		try {
			Files.setLastModifiedTime(target,
					FileTime.from(source.modified().atZone(ZoneId.systemDefault()).toInstant()));
		} catch (IOException | RuntimeException ignored) {
			// Metadata preservation is best effort; payload extraction already succeeded.
		}
	}

	private static long totalFileBytes(IsoEntry entry) {
		if (!entry.directory()) {
			return entry.size();
		}
		return entry.children().stream().mapToLong(IsoExtractor::totalFileBytes)
				.reduce(0L, IsoExtractor::saturatedAdd);
	}

	private static long saturatedAdd(long first, long second) {
		if (first > Long.MAX_VALUE - second) {
			return Long.MAX_VALUE;
		}
		return first + second;
	}

	private static void checkCancelled(NuclrPluginCallback callback) throws ExtractionCancelledException {
		if (Thread.currentThread().isInterrupted() || callback != null && callback.isCancelled()) {
			throw new ExtractionCancelledException();
		}
	}

	static final class ExtractionCancelledException extends IOException {
		private static final long serialVersionUID = 1L;

		ExtractionCancelledException() {
			super("ISO extraction was cancelled.");
		}
	}
}
