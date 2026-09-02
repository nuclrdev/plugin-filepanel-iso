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

	private volatile NuclrPluginContext context;
	private volatile IsoFileSystem fileSystem;
	private volatile IsoNuclrResource currentFolder;
	private volatile NuclrResource sourceResource;
	private volatile Path materializedImage;
	private volatile String displayName;
	private volatile boolean focused;
	private volatile boolean opening;
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

		synchronized (lifecycleLock) {
			if (unloading || opening || fileSystem != null || !isIsoResource(resource)) {
				return null;
			}
			opening = true;
			sourceResource = resource;
			Path sourcePath = resource.getPath();
			displayName = resourceName(resource,
					sourcePath != null ? sourcePath : Path.of("image.iso"));
		}
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
			IsoNuclrResource root = IsoNuclrResource.of(context, opened, opened.root(), uuid);
			synchronized (lifecycleLock) {
				if (unloading || isCancelled(cancelled)) {
					opened.close();
					deleteMaterializedImage();
					emitClosed();
					return null;
				}
				fileSystem = opened;
				currentFolder = root;
				closing = false;
			}
			LOG.info("Opened {} image {}", opened.format(), image);
			return listDirectory(root);
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
		} finally {
			opening = false;
		}
	}

	private NuclrResourceData listDirectory(IsoNuclrResource folder) {
		IsoFileSystem mounted = fileSystem;
		if (mounted == null || folder.entry() == null || !folder.belongsTo(uuid)) {
			return null;
		}
		try {
			List<IsoEntry> entries = new ArrayList<>(mounted.list(folder.entry()));
			entries.sort(Comparator.comparing(IsoEntry::directory).reversed()
					.thenComparing(IsoEntry::name, String.CASE_INSENSITIVE_ORDER));

			var data = new NuclrResourceData();
			data.setColumnNames(COLUMN_NAMES);
			if ("/".equals(folder.entryPath())) {
				data.getEntries().add(IsoNuclrResource.closeEntry(context, mounted, uuid));
			} else {
				data.getEntries().add(IsoNuclrResource.parent(
						context, mounted, mounted.parent(folder.entry()), uuid));
			}
			for (IsoEntry entry : entries) {
				data.getEntries().add(IsoNuclrResource.of(context, mounted, entry, uuid));
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
			IsoFileSystem mounted = fileSystem;
			return !closing && !unloading && mounted != null && isoResource.belongsTo(uuid) && isoResource.isFolder()
					&& (isoResource.entry() != null
							|| isoResource.getMetadata(IsoNuclrResource.KEY_CLOSE_ISO, Boolean.FALSE));
		}
		return !closing && !unloading && !opening && fileSystem == null && isIsoResource(resource);
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
		IsoFileSystem sourceFileSystem = fileSystem;
		if (sourceFileSystem == null || sourceFileSystem.isClosed()) {
			reportExtractionError("selection", new IOException("The ISO panel has been closed."), callback);
			return;
		}
		Path destinationSnapshot = destination.toAbsolutePath().normalize();
		runTransfer(() -> {
			try {
				if (IsoExtractor.extract(sourceFileSystem, entries, destinationSnapshot,
						callback, this::resolveExtractionConflict)) {
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
		IsoFileSystem mounted = fileSystem;
		if (mounted == null || mounted.isClosed()) {
			throw new IOException("The ISO panel has been closed.");
		}
		walkDescendants(mounted, isoResource.entry(), visitor, cancelled, recursive);
	}

	private void walkDescendants(IsoFileSystem mounted, IsoEntry directory,
			Consumer<NuclrResource> visitor, AtomicBoolean cancelled, boolean recursive) throws IOException {
		for (IsoEntry child : mounted.list(directory)) {
			if (isCancelled(cancelled)) {
				return;
			}
			IsoNuclrResource childResource = IsoNuclrResource.of(context, mounted, child, uuid);
			visitor.accept(childResource);
			if (recursive && child.directory()) {
				walkDescendants(mounted, child, visitor, cancelled, true);
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
		items.add(menu("Left Panel", "Alt+F1", "left"));
		items.add(menu("Right Panel", "Alt+F2", "right"));
		items.add(menu("Find", "Alt+F7", "find"));
		items.add(menu("History", "Alt+F8", "history"));
		items.add(menu("Fullscreen", "Alt+F9", "fullscreen"));
		items.add(menu("Tree", "Alt+F10", "tree"));
		items.add(menu("View History", "Alt+F11", "viewHistory"));
		items.add(menu("Folder History", "Alt+F12", "folderHistory"));
		items.add(menu("Hide Left", "Ctrl+F1", "hideLeft"));
		items.add(menu("Hide Right", "Ctrl+F2", "hideRight"));
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
		String imageName = displayName;
		IsoNuclrResource folder = currentFolder;
		if (imageName == null) {
			return "";
		}
		String path = folder != null && folder.entryPath() != null ? folder.entryPath() : "/";
		return imageName + ":" + path;
	}

	@Override
	public String getWindowTitle() {
		IsoFileSystem mounted = fileSystem;
		IsoNuclrResource folder = currentFolder;
		if (mounted == null) {
			return getCurrentLocationDisplayText();
		}
		String path = folder != null && folder.entryPath() != null ? folder.entryPath() : "/";
		return mounted.image() + ":" + path;
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

	private Path localImage(NuclrResource resource, AtomicBoolean cancelled) throws IOException {
		Path path = resource.getPath();
		if (path != null && path.getFileSystem().equals(java.nio.file.FileSystems.getDefault())
				&& Files.isRegularFile(path)) {
			return path;
		}

		String suffix = "-" + safeTempName(resource.getName());
		Path temp = Files.createTempFile("nuclr-iso-", suffix);
		temp.toFile().deleteOnExit();
		try (InputStream input = resource.openInputStream();
				OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
			byte[] buffer = new byte[COPY_BUFFER_SIZE];
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (unloading || isCancelled(cancelled) || Thread.currentThread().isInterrupted()) {
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
		synchronized (lifecycleLock) {
			if (unloading) {
				Files.deleteIfExists(temp);
				throw new IOException("ISO opening was cancelled.");
			}
			materializedImage = temp;
		}
		return temp;
	}

	private void emitClosed() {
		closing = true;
		NuclrPluginContext pluginContext = context;
		NuclrResource source = sourceResource;
		if (pluginContext == null || pluginContext.getEventBus() == null) {
			return;
		}
		var event = new HashMap<String, Object>();
		event.put("uuid", uuid);
		if (source != null) {
			event.put("selectionResource", source);
		}
		pluginContext.getEventBus().emit(this, EVENT_PLUGIN_UNLOAD, event);
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
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(cause);
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
		IsoFileSystem opened;
		synchronized (lifecycleLock) {
			opened = fileSystem;
			fileSystem = null;
			currentFolder = null;
		}
		if (opened != null) {
			opened.close();
		}
	}

	private void deleteMaterializedImage() {
		Path temp;
		synchronized (lifecycleLock) {
			temp = materializedImage;
			materializedImage = null;
		}
		if (temp != null) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException e) {
				LOG.warn("Unable to delete temporary ISO image {}", temp, e);
			}
		}
	}

	private IsoExtractor.ConflictAction resolveExtractionConflict(IsoEntry source, Path target) {
		if (unloading || Thread.currentThread().isInterrupted() || GraphicsEnvironment.isHeadless()) {
			return IsoExtractor.ConflictAction.CANCEL;
		}
		var result = new IsoExtractor.ConflictAction[1];
		Runnable prompt = () -> {
			if (unloading) {
				result[0] = IsoExtractor.ConflictAction.CANCEL;
				return;
			}
			Object[] choices = { "Overwrite", "Skip", "Keep Both", "Cancel" };
			int choice = JOptionPane.showOptionDialog(null,
					"A destination item named \"" + source.name() + "\" already exists.",
					"Confirm extraction", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
					null, choices, choices[0]);
			result[0] = switch (choice) {
				case 0 -> IsoExtractor.ConflictAction.OVERWRITE;
				case 1 -> IsoExtractor.ConflictAction.SKIP;
				case 2 -> IsoExtractor.ConflictAction.KEEP_BOTH;
				default -> IsoExtractor.ConflictAction.CANCEL;
			};
		};
		if (SwingUtilities.isEventDispatchThread()) {
			prompt.run();
		} else {
			try {
				SwingUtilities.invokeAndWait(prompt);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return IsoExtractor.ConflictAction.CANCEL;
			} catch (java.lang.reflect.InvocationTargetException e) {
				LOG.warn("Unable to show the ISO extraction conflict dialog for {}", target, e.getCause());
				return IsoExtractor.ConflictAction.CANCEL;
			}
		}
		return result[0] != null ? result[0] : IsoExtractor.ConflictAction.CANCEL;
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
