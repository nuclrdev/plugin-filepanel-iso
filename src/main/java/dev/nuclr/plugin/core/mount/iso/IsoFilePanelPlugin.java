/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.mount.iso;

import java.awt.GraphicsEnvironment;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;

/** Read-only FilePanel for ISO 9660, Rock Ridge, Joliet, and UDF images. */
public final class IsoFilePanelPlugin implements FilePanelNuclrPlugin {

	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.mount.iso";
	static final List<String> COLUMN_NAMES = List.of("Name", "Size", "Date", "Time");

	private static final Logger LOG = LoggerFactory.getLogger(IsoFilePanelPlugin.class);
	private static final String ACTION_VIEW = "filepanel.view";
	private static final String ACTION_COPY = "filepanel.copy";
	private static final String ACTION_ACCEPT_COPY = "accept.copy";
	private static final String EVENT_MAIN_PANEL_VIEW = "mainpanel.view";
	private static final String EVENT_PLUGIN_UNLOAD = "plugin.unload";
	private static final String EVENT_REFRESH_PANEL = "refresh.plugin.file.panel";
	private static final int COPY_BUFFER_SIZE = 64 * 1024;

	private final String uuid = UUID.randomUUID().toString();
	private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("iso-extract-" + uuid).factory());
	private final Object lifecycleLock = new Object();

	private NuclrPluginContext context;
	private IsoFileSystem fileSystem;
	private IsoNuclrResource currentFolder;
	private NuclrResource sourceResource;
	private Path materializedImage;
	private String displayName;
	private boolean focused;
	private volatile boolean closing;
	private volatile boolean unloading;

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void init() {
		LOG.info("ISO panel plugin loaded");
	}

	@Override
	public void unload() {
		synchronized (lifecycleLock) {
			unloading = true;
		}
		transferExecutor.shutdownNow();
		awaitTransfers();
		closeFileSystem();
		deleteMaterializedImage();
		LOG.info("ISO panel plugin unloaded");
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (resource == null || isCancelled(cancelled)) {
			return null;
		}

		if (resource.getMetadata(IsoNuclrResource.KEY_CLOSE_ISO, Boolean.FALSE)) {
			emitClosed();
			return null;
		}

		if (resource instanceof IsoNuclrResource isoResource && isoResource.belongsTo(uuid)) {
			if (isoResource.isFolder() && isoResource.entry() != null) {
				return listDirectory(isoResource);
			}
			return null;
		}

		if (fileSystem != null || !isIsoResource(resource)) {
			return null;
		}

		this.sourceResource = resource;
		Path sourcePath = resource.getPath();
		this.displayName = resourceName(resource,
				sourcePath != null ? sourcePath : Path.of("image.iso"));
		try {
			Path image = localImage(resource, cancelled);
			if (isCancelled(cancelled)) {
				deleteMaterializedImage();
				emitClosed();
				return null;
			}
			IsoFileSystem opened = new IsoFileSystem(image);
			if (isCancelled(cancelled)) {
				opened.close();
				deleteMaterializedImage();
				emitClosed();
				return null;
			}
			this.fileSystem = opened;
			this.currentFolder = IsoNuclrResource.of(context, opened, opened.root(), uuid);
			this.closing = false;
			LOG.info("Opened {} image {}", opened.format(), image);
			return listDirectory(currentFolder);
		} catch (IOException | RuntimeException e) {
			if (isCancelled(cancelled) || Thread.currentThread().isInterrupted()) {
				LOG.debug("ISO opening cancelled for {}", resource.getName());
				closeFileSystem();
				deleteMaterializedImage();
				emitClosed();
				return null;
			}
			LOG.error("Unable to open ISO resource {}", resource.getName(), e);
			closeFileSystem();
			deleteMaterializedImage();
			showError("Unable to open ISO image",
					"The ISO filesystem is unsupported or the file is damaged.");
			emitClosed();
			return null;
		}
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resource, AtomicBoolean cancelled, EntrySink sink) {
		NuclrResourceData data = openResource(resource, cancelled);
		if (data != null && sink != null) {
			sink.columns(data.getColumnNames());
			for (NuclrResource entry : data.getEntries()) {
				if (isCancelled(cancelled)) {
					break;
				}
				sink.add(entry);
			}
		}
		return data;
	}

	private NuclrResourceData listDirectory(IsoNuclrResource folder) {
		if (fileSystem == null || folder.entry() == null) {
			return null;
		}
		try {
			List<IsoEntry> entries = new ArrayList<>(fileSystem.list(folder.entry()));
			entries.sort(Comparator.comparing(IsoEntry::directory).reversed()
					.thenComparing(IsoEntry::name, String.CASE_INSENSITIVE_ORDER));

			var data = new NuclrResourceData();
			data.setColumnNames(COLUMN_NAMES);
			if ("/".equals(folder.entryPath())) {
				data.getEntries().add(IsoNuclrResource.closeEntry(context, fileSystem, uuid));
			} else {
				data.getEntries().add(IsoNuclrResource.parent(
						context, fileSystem, fileSystem.parent(folder.entry()), uuid));
			}
			for (IsoEntry entry : entries) {
				data.getEntries().add(IsoNuclrResource.of(context, fileSystem, entry, uuid));
			}
			this.currentFolder = folder;
			return data;
		} catch (IOException e) {
			LOG.error("Unable to list ISO directory {}", folder.entryPath(), e);
			showError("Unable to read ISO directory", "The ISO directory could not be read.");
			return null;
		}
	}

	@Override
	public boolean supports(NuclrResource resource) {
		if (resource instanceof IsoNuclrResource isoResource) {
			return !closing && fileSystem != null && isoResource.belongsTo(uuid) && isoResource.isFolder()
					&& isoResource.entry() != null;
		}
		return !closing && fileSystem == null && isIsoResource(resource);
	}

	private static boolean isIsoResource(NuclrResource resource) {
		if (resource == null || resource.isFolder()) {
			return false;
		}
		String name = resource.getName();
		if ((name == null || name.isBlank()) && resource.getPath() != null
				&& resource.getPath().getFileName() != null) {
			name = resource.getPath().getFileName().toString();
		}
		return name != null && name.toLowerCase(Locale.ROOT).endsWith(".iso");
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		if (ACTION_VIEW.equals(actionType)) {
			openInViewer(focusedResource);
			return;
		}
		if (ACTION_COPY.equals(actionType)) {
			copyTo(other, selectedResources, focusedResource, callback);
			return;
		}
		if (ACTION_ACCEPT_COPY.equals(actionType) || isMutationAction(actionType)) {
			reportUnsupported(actionType, callback);
		}
	}

	private void openInViewer(NuclrResource resource) {
		if (!(resource instanceof IsoNuclrResource) || resource.isFolder()) {
			return;
		}
		if (context != null && context.getEventBus() != null) {
			context.getEventBus().emit(EVENT_MAIN_PANEL_VIEW, Map.of("resource", resource), null);
		}
	}

	private void copyTo(BaseNuclrPlugin destinationPlugin, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, NuclrPluginCallback callback) {
		if (destinationPlugin == null || destinationPlugin == this
				|| destinationPlugin instanceof QuickViewNuclrPlugin) {
			reportUnsupported(ACTION_COPY, callback);
			return;
		}
		NuclrResource destinationResource = destinationPlugin.getCurrentResource();
		Path destination = destinationResource != null ? destinationResource.getPath() : null;
		if (destination == null || !Files.isDirectory(destination)) {
			IOException error = new IOException("The destination panel is not a local filesystem directory.");
			reportExtractionError("selection", error, callback);
			return;
		}

		List<IsoEntry> entries = selectedEntries(selectedResources, focusedResource);
		if (entries.isEmpty()) {
			return;
		}
		Path destinationSnapshot = destination.toAbsolutePath().normalize();
		runTransfer(() -> {
			try {
				if (IsoExtractor.extract(fileSystem, entries, destinationSnapshot, callback)) {
					requestPanelRefresh(destinationPlugin.uuid());
				}
			} catch (IsoExtractor.ExtractionCancelledException e) {
				LOG.debug("ISO extraction cancelled");
			} catch (IOException | RuntimeException e) {
				String item = entries.size() == 1 ? entries.get(0).name() : "selected items";
				reportExtractionError(item, e, callback);
			}
		});
	}

	private List<IsoEntry> selectedEntries(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		List<NuclrResource> chosen = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources
				: focusedResource != null ? List.of(focusedResource) : List.of();
		var result = new ArrayList<IsoEntry>();
		for (NuclrResource resource : chosen) {
			if (resource instanceof IsoNuclrResource isoResource && isoResource.belongsTo(uuid)
					&& isoResource.entry() != null && !"..".equals(resource.getName())) {
				result.add(isoResource.entry());
			}
		}
		return result;
	}

	@Override
	public void walkDescendants(NuclrResource resource, Consumer<NuclrResource> visitor,
			AtomicBoolean cancelled, boolean recursive) throws IOException {
		if (!(resource instanceof IsoNuclrResource isoResource) || !isoResource.belongsTo(uuid)
				|| isoResource.entry() == null || !isoResource.entry().directory()) {
			throw new IOException("The resource is not an ISO directory.");
		}
		for (IsoEntry child : fileSystem.list(isoResource.entry())) {
			if (isCancelled(cancelled)) {
				return;
			}
			IsoNuclrResource childResource = IsoNuclrResource.of(context, fileSystem, child, uuid);
			visitor.accept(childResource);
			if (recursive && child.directory()) {
				walkDescendants(childResource, visitor, cancelled, true);
			}
		}
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource source) {
		var items = new ArrayList<NuclrMenuResource>();
		if (source != null && !source.isFolder()) {
			items.add(menu("View", "F3", ACTION_VIEW));
		}
		items.add(menu("Extract", "F5", ACTION_COPY));
		items.add(menu("Quit", "F10", "quit"));
		items.add(menu("Plugins", "F11", "plugins"));
		items.add(menu("Screen", "F12", "screen"));
		items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
		items.add(menu("Extension", "Ctrl+F4", "filepanel.sort:ext"));
		items.add(menu("Date", "Ctrl+F5", "filepanel.sort:modified:Date"));
		items.add(menu("Size", "Ctrl+F6", "filepanel.sort:size:Size"));
		items.add(menu("Unsort", "Ctrl+F7", "filepanel.sort:unsorted"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
		return items;
	}

	private static NuclrMenuResource menu(String name, String key, String event) {
		return new NuclrMenuResource(name, key, event);
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentFolder;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		if (displayName == null) {
			return "";
		}
		String path = currentFolder != null && currentFolder.entryPath() != null
				? currentFolder.entryPath() : "/";
		return displayName + ":" + path;
	}

	@Override
	public String getWindowTitle() {
		if (fileSystem == null) {
			return getCurrentLocationDisplayText();
		}
		String path = currentFolder != null && currentFolder.entryPath() != null
				? currentFolder.entryPath() : "/";
		return fileSystem.image() + ":" + path;
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}
		if (selectedResources.size() == 1) {
			NuclrResource resource = selectedResources.get(0);
			return resource.getName() + "  |  "
					+ (resource.isFolder() ? "Folder" : IsoNuclrResource.displaySize(resource.getLength()));
		}
		long bytes = 0L;
		int files = 0;
		int folders = 0;
		for (NuclrResource resource : selectedResources) {
			if (resource.isFolder()) {
				folders++;
			} else {
				files++;
				bytes = saturatedAdd(bytes, resource.getLength());
			}
		}
		return "Bytes: " + IsoNuclrResource.displaySize(bytes) + ",  files: " + files + ",  folders: " + folders;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		// The adapter belongs to the panel instance and is released by unload().
	}

	@Override
	public String uuid() {
		return uuid;
	}

	IsoFileSystem mountedFileSystem() {
		return fileSystem;
	}

	private Path localImage(NuclrResource resource, AtomicBoolean cancelled) throws IOException {
		Path path = resource.getPath();
		if (path != null && path.getFileSystem().equals(java.nio.file.FileSystems.getDefault())
				&& Files.isRegularFile(path)) {
			return path;
		}

		String suffix = "-" + safeTempName(resource.getName());
		Path temp = Files.createTempFile("nuclr-iso-", suffix);
		try (InputStream input = resource.openInputStream();
				OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
			byte[] buffer = new byte[COPY_BUFFER_SIZE];
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (isCancelled(cancelled) || Thread.currentThread().isInterrupted()) {
					throw new IOException("ISO opening was cancelled.");
				}
				if (read > 0) {
					output.write(buffer, 0, read);
				}
			}
		} catch (Exception e) {
			Files.deleteIfExists(temp);
			throw e instanceof IOException io ? io : new IOException("Unable to read the ISO resource.", e);
		}
		this.materializedImage = temp;
		return temp;
	}

	private void emitClosed() {
		closing = true;
		if (context == null || context.getEventBus() == null) {
			return;
		}
		var event = new HashMap<String, Object>();
		event.put("uuid", uuid);
		if (sourceResource != null) {
			event.put("selectionResource", sourceResource);
		}
		context.getEventBus().emit(this, EVENT_PLUGIN_UNLOAD, event);
	}

	private void requestPanelRefresh(String pluginUuid) {
		if (context == null || context.getEventBus() == null || pluginUuid == null) {
			return;
		}
		Runnable emit = () -> context.getEventBus().emit(
				EVENT_REFRESH_PANEL, Map.of("plugin.uuid", pluginUuid), null);
		if (SwingUtilities.isEventDispatchThread()) {
			emit.run();
		} else {
			SwingUtilities.invokeLater(emit);
		}
	}

	private void runTransfer(Runnable transfer) {
		synchronized (lifecycleLock) {
			if (unloading) {
				return;
			}
		}
		if (SwingUtilities.isEventDispatchThread()) {
			try {
				transferExecutor.execute(transfer);
			} catch (RejectedExecutionException ignored) {
				LOG.debug("Ignoring extraction requested while the ISO panel is closing");
			}
			return;
		}

		Future<?> future;
		try {
			future = transferExecutor.submit(transfer);
		} catch (RejectedExecutionException ignored) {
			return;
		}
		try {
			future.get();
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			throw new IllegalStateException(e.getCause());
		}
	}

	private void awaitTransfers() {
		if (transferExecutor.isTerminated()) {
			return;
		}
		if (!SwingUtilities.isEventDispatchThread()) {
			awaitTransfersOffEdt();
			return;
		}
		SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
		Thread.startVirtualThread(() -> {
			awaitTransfersOffEdt();
			SwingUtilities.invokeLater(loop::exit);
		});
		loop.enter();
	}

	private void awaitTransfersOffEdt() {
		boolean interrupted = false;
		while (!transferExecutor.isTerminated()) {
			try {
				if (transferExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
					break;
				}
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void closeFileSystem() {
		IsoFileSystem opened = fileSystem;
		fileSystem = null;
		currentFolder = null;
		if (opened != null) {
			opened.close();
		}
	}

	private void deleteMaterializedImage() {
		Path temp = materializedImage;
		materializedImage = null;
		if (temp != null) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException e) {
				LOG.warn("Unable to delete temporary ISO image {}", temp, e);
			}
		}
	}

	private void reportExtractionError(String item, Exception error, NuclrPluginCallback callback) {
		LOG.error("Unable to extract {} from {}", item, displayName, error);
		if (callback != null) {
			callback.onError("Unable to extract " + item, error);
		}
		showError("Unable to extract " + item, "The ISO image could not be read completely or the destination could not be written.");
	}

	private void reportUnsupported(String action, NuclrPluginCallback callback) {
		var error = new UnsupportedOperationException("ISO images are read-only.");
		LOG.debug("Rejected unsupported ISO action {}", action);
		if (callback != null) {
			callback.onError("ISO images are read-only", error);
		}
		showError("Read-only ISO", "This operation is not supported inside an ISO image.");
	}

	private static boolean isMutationAction(String action) {
		return "filepanel.edit".equals(action) || "filepanel.move".equals(action)
				|| "accept.move".equals(action) || "filepanel.delete".equals(action)
				|| "filepanel.deletePermanent".equals(action) || "filepanel.makeFolder".equals(action)
				|| "clipboard.paste".equals(action);
	}

	private static void showError(String title, String message) {
		if (GraphicsEnvironment.isHeadless()) {
			return;
		}
		Runnable show = () -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
		if (SwingUtilities.isEventDispatchThread()) {
			show.run();
		} else {
			SwingUtilities.invokeLater(show);
		}
	}

	private static String resourceName(NuclrResource resource, Path image) {
		if (resource.getName() != null && !resource.getName().isBlank()) {
			return resource.getName();
		}
		return image.getFileName() != null ? image.getFileName().toString() : image.toString();
	}

	private static String safeTempName(String name) {
		String value = name == null || name.isBlank() ? "image.iso" : name;
		return value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	private static long saturatedAdd(long first, long second) {
		return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
	}
}
