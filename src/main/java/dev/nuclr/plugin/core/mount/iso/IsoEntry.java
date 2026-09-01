/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.mount.iso;

import java.time.LocalDateTime;
import java.util.List;

import com.palantir.isofilereader.isofilereader.GenericInternalIsoFile;

/**
 * Lightweight immutable metadata for one path in an ISO image.
 *
 * <p>{@code readable} is {@code false} for an entry the reader can describe but
 * cannot stream — a UDF file whose payload is embedded in its ICB, or one spread
 * over allocation descriptors the upstream reader does not follow. Such an entry
 * is still listed, with its true size, so the user sees what the image contains;
 * opening or extracting it fails with an explicit message rather than quietly
 * yielding the wrong bytes. See {@link IsoFileSystem#payload}.
 */
record IsoEntry(
		String path,
		String name,
		boolean directory,
		long size,
		boolean readable,
		LocalDateTime modified,
		GenericInternalIsoFile source,
		List<IsoEntry> children) {

	IsoEntry {
		children = List.copyOf(children);
	}

	static IsoEntry root(GenericInternalIsoFile source, List<IsoEntry> children) {
		return new IsoEntry("/", "", true, 0L, true, null, source, children);
	}
}
