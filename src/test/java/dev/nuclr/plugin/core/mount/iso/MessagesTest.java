package dev.nuclr.plugin.core.mount.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MessagesTest {

	/** Every translation shipped with the plugin. Add a language here when adding its file. */
	private static final List<String> TRANSLATIONS = List.of("be", "uk");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");
	private static final Pattern TR_CALL = Pattern.compile("\\btr\\(\\s*\"([^\"]+)\"\\s*[,)]");

	@AfterEach
	void backToEnglish() {
		Messages.use(Locale.ENGLISH);
	}

	private static Properties bundle(String suffix) throws IOException {
		String name = "/" + Messages.BUNDLE.replace('.', '/') + suffix + ".properties";
		try (InputStream in = MessagesTest.class.getResourceAsStream(name)) {
			assertTrue(in != null, "missing " + name);
			Properties properties = new Properties();
			properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			return properties;
		}
	}

	private static Set<String> placeholders(String text) {
		Set<String> found = new TreeSet<>();
		Matcher matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		return found;
	}

	@Test
	void everyTranslationHasExactlyTheEnglishKeysAndPlaceholders() throws IOException {
		Properties english = bundle("");
		for (String language : TRANSLATIONS) {
			Properties translation = bundle("_" + language);
			assertEquals(new TreeSet<>(english.stringPropertyNames()), new TreeSet<>(translation.stringPropertyNames()),
					language + " keys differ from English");
			for (String key : english.stringPropertyNames()) {
				String text = translation.getProperty(key);
				assertFalse(text.isBlank(), language + " " + key + " is blank");
				assertEquals(placeholders(english.getProperty(key)), placeholders(text),
						language + " " + key + " placeholders differ");
			}
		}
	}

	@Test
	void belarusianUsesTypographicApostrophes() throws IOException {
		Properties belarusian = bundle("_be");
		for (String key : belarusian.stringPropertyNames()) {
			String text = belarusian.getProperty(key);
			assertFalse(text.replace("''", "").contains("'"),
					"be " + key + " has an ASCII apostrophe, which MessageFormat treats as a quote: " + text);
		}
	}

	@Test
	void everyKeyUsedInTheCodeExistsInEnglish() throws IOException {
		Properties english = bundle("");
		List<String> missing = new ArrayList<>();
		try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
			for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
				Matcher matcher = TR_CALL.matcher(Files.readString(source));
				while (matcher.find()) {
					if (!english.containsKey(matcher.group(1))) {
						missing.add(source.getFileName() + ": " + matcher.group(1));
					}
				}
			}
		}
		assertEquals(List.of(), missing);
	}

	@Test
	void picksTheRequestedLanguageAndFallsBackToEnglish() {
		Messages.use(Locale.forLanguageTag("be"));
		assertEquals("Распак", Messages.tr("menu.extract"));

		Messages.use(Locale.GERMAN);
		assertEquals("Extract", Messages.tr("menu.extract"));

		assertEquals("no.such.key", Messages.tr("no.such.key"));
	}
}
