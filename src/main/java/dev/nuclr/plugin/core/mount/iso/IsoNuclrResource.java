/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.mount.iso;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.OpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;

/** Nuclr resource backed by an entry in a live {@link IsoFileSystem}. */
final class IsoNuclrResource extends NuclrResource {

	static final String KEY_CLOSE_ISO = "iso.close";
	private static final long serialVersionUID = 1L;

	private final transient IsoFileSystem fileSystem;
	private final transient IsoEntry entry;
	private final String ownerId;
	private final String entryPath;

	private IsoNuclrResource(NuclrPluginContext context, IsoFileSystem fileSystem,
			IsoEntry entry, String ownerId, String displayName) {
		super(null);
		this.fileSystem = fileSystem;
		this.entry = entry;
		this.ownerId = ownerId;
		this.entryPath = entry != null ? entry.path() : null;
		this.name = displayName;
		this.fullPath = entryPath != null ? fileSystem.image() + ":" + entryPath : "..";
		this.uuid = ownerId + ":" + (entryPath != null ? entryPath : "close");
		this.folder = entry == null || entry.directory();
		this.length = entry != null ? entry.size() : 0L;
		this.lastModifiedDateTime = entry != null ? entry.modified() : null;
		this.createdDateTime = null;
		this.lastAccessDateTime = null;
		this.readable = true;
		addColumns(context);
	}

	static IsoNuclrResource of(NuclrPluginContext context, IsoFileSystem fileSystem,
			IsoEntry entry, String ownerId) {
		String name = "/".equals(entry.path())
				? fileSystem.image().getFileName().toString()
				: entry.name();
		return new IsoNuclrResource(context, fileSystem, entry, ownerId, name);
	}

	static IsoNuclrResource parent(NuclrPluginContext context, IsoFileSystem fileSystem,
			IsoEntry parent, String ownerId) {
		return new IsoNuclrResource(context, fileSystem, parent, ownerId, "..");
	}

	static IsoNuclrResource closeEntry(NuclrPluginContext context, IsoFileSystem fileSystem, String ownerId) {
		var resource = new IsoNuclrResource(context, fileSystem, null, ownerId, "..");
		resource.metadata.put(KEY_CLOSE_ISO, Boolean.TRUE);
		return resource;
	}

	boolean belongsTo(String pluginUuid) {
		return ownerId.equals(pluginUuid);
	}

	IsoEntry entry() {
		return entry;
	}

	String entryPath() {
		return entryPath;
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (folder || fileSystem == null || entry == null) {
			throw new IOException("The ISO resource is not a file.");
		}
		if (options != null && options.length > 0) {
			for (OpenOption option : options) {
				if (option != java.nio.file.StandardOpenOption.READ) {
					throw new IOException("ISO resources are read-only.");
				}
			}
		}
		return fileSystem.open(entry);
	}

	private void addColumns(NuclrPluginContext context) {
		Locale locale = context != null && context.getLocale() != null ? context.getLocale() : Locale.getDefault();
		metadata.put("Name", name);
		metadata.put("Size", folder ? "Folder" : displaySize(length));
		metadata.put("Date", formatDate(locale, lastModifiedDateTime));
		metadata.put("Time", formatTime(locale, lastModifiedDateTime));
		metadata.put("iso.readOnly", Boolean.TRUE);
	}

	private static String formatDate(Locale locale, LocalDateTime value) {
		return value == null ? "" : value.toLocalDate()
				.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale));
	}

	private static String formatTime(Locale locale, LocalDateTime value) {
		return value == null ? "" : value.toLocalTime()
				.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale));
	}

	static LocalDateTime epoch() {
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

	static String displaySize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		double value = bytes;
		String[] units = { "KB", "MB", "GB", "TB", "PB" };
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
	}
}
