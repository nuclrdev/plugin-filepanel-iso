package dev.nuclr.plugin.core.mount.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;

class IsoFilePanelPluginTest {

	@TempDir
	Path tempDir;

	@Test
	void detectsOpensAndNavigatesAnIso() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("PANEL.ISO"));
		var bus = new RecordingEventBus();
		var plugin = plugin(bus);
		var source = new TestResource(image);
		try {
			assertTrue(plugin.supports(source));
			assertFalse(plugin.supports(new TestResource(Files.writeString(tempDir.resolve("note.txt"), "x"))));

			var root = plugin.openResource(source, new AtomicBoolean());
			assertNotNull(root);
			assertEquals(List.of("..", "FOLDER", "LARGE.BIN", "README.TXT"),
					root.getEntries().stream().map(NuclrResource::getName).toList());
			assertEquals("PANEL.ISO:/", plugin.getCurrentLocationDisplayText());

			NuclrResource folder = named(root, "FOLDER");
			assertTrue(plugin.supports(folder));
			var nested = plugin.openResource(folder, new AtomicBoolean());
			assertEquals(List.of("..", "NESTED.TXT"),
					nested.getEntries().stream().map(NuclrResource::getName).toList());
			assertEquals("PANEL.ISO:/FOLDER", plugin.getCurrentLocationDisplayText());

			NuclrResource file = named(nested, "NESTED.TXT");
			try (InputStream input = file.openInputStream()) {
				assertEquals("nested content", new String(input.readAllBytes()));
			}
			plugin.act(null, "filepanel.view", List.of(), file, Map.of(), null);
			assertEquals("mainpanel.view", bus.type);
			assertEquals(file, bus.event.get("resource"));
		} finally {
			plugin.unload();
		}
	}

	@Test
	void mutationActionsAreExplicitlyUnsupported() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("readonly.iso"));
		var plugin = plugin(new RecordingEventBus());
		var callback = new RecordingCallback();
		try {
			plugin.openResource(new TestResource(image), new AtomicBoolean());
			plugin.act(null, "filepanel.delete", List.of(), null, Map.of(), callback);
			assertNotNull(callback.error);
			assertTrue(callback.error instanceof UnsupportedOperationException);
			assertFalse(plugin.menuItems(plugin.getCurrentResource()).stream()
					.anyMatch(item -> List.of("F4", "F6", "F7", "F8").contains(item.getFunctionKey())));
		} finally {
			plugin.unload();
		}
	}

	@Test
	void corruptIsoReturnsNoListingAndRequestsPanelClose() throws Exception {
		Path image = Files.writeString(tempDir.resolve("corrupt.iso"), "broken");
		var bus = new RecordingEventBus();
		var plugin = plugin(bus);
		try {
			assertNull(plugin.openResource(new TestResource(image), new AtomicBoolean()));
			assertEquals("plugin.unload", bus.type);
		} finally {
			plugin.unload();
		}
	}

	@Test
	void f5StreamsTheSelectionToTheOppositeLocalPanelAndRefreshesIt() throws Exception {
		Path image = IsoTestImage.create(tempDir.resolve("copy.iso"));
		Path destination = Files.createDirectory(tempDir.resolve("destination"));
		var bus = new RecordingEventBus();
		var plugin = plugin(bus);
		var callback = new RecordingCallback();
		try {
			var root = plugin.openResource(new TestResource(image), new AtomicBoolean());
			NuclrResource readme = named(root, "README.TXT");
			var otherPanel = new FakeLocalPanel(new TestResource(destination));

			plugin.act(otherPanel, "filepanel.copy", List.of(readme), readme, new java.util.HashMap<>(), callback);
			SwingUtilities.invokeAndWait(() -> { });

			assertEquals("root file\n", Files.readString(destination.resolve("README.TXT")));
			assertTrue(callback.completed);
			assertTrue(callback.progress > 0);
			assertEquals("refresh.plugin.file.panel", bus.type);
			assertEquals(otherPanel.uuid(), bus.event.get("plugin.uuid"));
		} finally {
			plugin.unload();
		}
	}

	private static NuclrResource named(IsoFilePanelPlugin.NuclrResourceData data, String name) {
		return data.getEntries().stream().filter(resource -> name.equals(resource.getName())).findFirst().orElseThrow();
	}

	private static IsoFilePanelPlugin plugin(RecordingEventBus bus) {
		var plugin = new IsoFilePanelPlugin();
		plugin.preinit(new TestContext(bus));
		plugin.init();
		return plugin;
	}

	private static final class TestResource extends NuclrResource {
		private static final long serialVersionUID = 1L;

		TestResource(Path path) throws Exception {
			super(path);
			setName(path.getFileName().toString());
			setFullPath(path.toAbsolutePath().toString());
			setUuid(path.toAbsolutePath().toString());
			setFolder(Files.isDirectory(path));
			setLength(Files.size(path));
		}

		@Override
		public InputStream openInputStream(OpenOption... options) throws Exception {
			return Files.newInputStream(getPath(), options);
		}
	}

	private record TestContext(NuclrEventBus eventBus) implements NuclrPluginContext {
		@Override public NuclrEventBus getEventBus() { return eventBus; }
		@Override public NuclrThemeScheme getTheme() { return null; }
		@Override public NuclrSettings getSettings() { return null; }
		@Override public Locale getLocale() { return Locale.US; }
	}

	private static final class RecordingEventBus implements NuclrEventBus {
		String type;
		Map<String, Object> event;

		@Override
		public void emit(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) {
			this.type = type;
			this.event = event;
		}

		@Override public void emit(Object source, String type, Map<String, Object> event) {
			emit(source, type, event, null);
		}
		@Override public void emit(String type, Map<String, Object> event, NuclrPluginCallback callback) {
			emit(null, type, event, callback);
		}
		@Override public void emit(String type, NuclrPluginCallback callback) { emit(null, type, Map.of(), callback); }
		@Override public void emit(String type) { emit(null, type, Map.of(), null); }
		@Override public void subscribe(NuclrEventListener listener) { }
		@Override public void unsubscribe(NuclrEventListener listener) { }
	}

	private static final class RecordingCallback implements NuclrPluginCallback {
		Exception error;
		long progress;
		boolean completed;
		@Override public void onStart(String description) { }
		@Override public void onProgress(long current, long total) { progress = current; }
		@Override public void onComplete() { completed = true; }
		@Override public void onError(String description, Exception e) { error = e; }
		@Override public boolean isCancelled() { return false; }
	}

	private static final class FakeLocalPanel implements FilePanelNuclrPlugin {
		private final NuclrResource current;
		private final String uuid = java.util.UUID.randomUUID().toString();

		FakeLocalPanel(NuclrResource current) { this.current = current; }
		@Override public NuclrResourceData openResource(NuclrResource resource, AtomicBoolean cancelled) { return null; }
		@Override public String getCurrentLocationDisplayText() { return current.getFullPath(); }
		@Override public String getSelectionSummaryText(List<NuclrResource> selectedResources) { return ""; }
		@Override public boolean onFocusGained() { return false; }
		@Override public void onFocusLost() { }
		@Override public boolean isFocused() { return false; }
		@Override public void preinit(NuclrPluginContext context) { }
		@Override public NuclrPluginContext getContext() { return null; }
		@Override public void init() { }
		@Override public String uuid() { return uuid; }
		@Override public void unload() { }
		@Override public void closeResource() { }
		@Override public NuclrResource getCurrentResource() { return current; }
		@Override public boolean supports(NuclrResource resource) { return false; }
		@Override public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) { }
	}
}
