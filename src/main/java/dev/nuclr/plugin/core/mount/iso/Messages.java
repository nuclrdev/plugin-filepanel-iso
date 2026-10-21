/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.mount.iso;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin's user-visible texts, in the language Commander asks for
 * ({@link dev.nuclr.platform.plugin.NuclrPluginContext#getLocale()}).
 *
 * <p>
 * English lives in {@code messages.properties} next to this class and translations in
 * {@code messages_<language>.properties}. A key missing from a translation falls back to English;
 * the operating system's language is never consulted, so a user who picked English in Commander
 * gets English on a Belarusian Windows.
 *
 * <p>
 * Texts with arguments go through {@link MessageFormat}: {@code {0}} is the first argument and a
 * literal apostrophe has to be written twice ({@code ''}). Texts without arguments are returned
 * exactly as written.
 */
public final class Messages {

	private static final Logger log = LoggerFactory.getLogger(Messages.class);

	static final String BUNDLE = "dev.nuclr.plugin.core.mount.iso.messages";

	private static final ResourceBundle.Control NO_SYSTEM_FALLBACK =
			ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

	private static volatile Locale locale = Locale.ENGLISH;
	private static volatile ResourceBundle bundle = load(Locale.ENGLISH);

	private Messages() {
	}

	/** Switches every text to {@code language}; called once by each plugin entry point on preinit. */
	public static void use(Locale language) {
		Locale target = language == null ? Locale.ENGLISH : language;
		bundle = load(target);
		locale = target;
	}

	/** The language texts and formatted numbers are currently in. */
	public static Locale locale() {
		return locale;
	}

	/** The text for {@code key}, or the key itself when no bundle has it. */
	public static String tr(String key) {
		try {
			return bundle.getString(key);
		} catch (MissingResourceException e) {
			log.warn("Missing message: {}", key);
			return key;
		}
	}

	/** The text for {@code key} with {@code {0}}, {@code {1}}… replaced by {@code args}. */
	public static String tr(String key, Object... args) {
		String pattern = tr(key);
		if (args == null || args.length == 0) {
			return pattern;
		}
		try {
			return new MessageFormat(pattern, locale).format(args);
		} catch (IllegalArgumentException e) {
			log.warn("Malformed message {}: {}", key, e.getMessage());
			return pattern;
		}
	}

	static ResourceBundle load(Locale language) {
		return ResourceBundle.getBundle(BUNDLE, language, Messages.class.getClassLoader(), NO_SYSTEM_FALLBACK);
	}
}
