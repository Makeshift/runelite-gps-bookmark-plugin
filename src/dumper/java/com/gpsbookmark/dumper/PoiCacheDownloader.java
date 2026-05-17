package com.gpsbookmark.dumper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Downloads an OSRS cache snapshot from openrs2 into a local cache root and
 * records the selected cache id for later parsing.
 */
public final class PoiCacheDownloader {
	public static void main(String[] args) throws IOException {
		Map<String, String> opts = parseArgs(args);

		Path cacheRoot = Paths.get(required(opts, "--cache-root"));
		Files.createDirectories(cacheRoot);

		String cacheId = opts.get("--openrs2-cache-id");
		if (cacheId == null || cacheId.isEmpty()) {
			cacheId = Openrs2CacheFetcher.resolveLatestCacheId();
		} else {
			System.out.println("Using openrs2 cache id " + cacheId + " (override)");
		}

		Path cacheHome = cacheRoot.resolve(cacheId);
		Openrs2CacheFetcher.ensureDownloaded(cacheId, cacheHome);

		String selectedCacheIdFile = required(opts, "--selected-cache-id-file");
		Path selectionFile = Paths.get(selectedCacheIdFile);
		Path parent = selectionFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(selectionFile, (cacheId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		System.out.println("Selected POI cache id recorded in " + selectionFile.toAbsolutePath());
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> opts = new LinkedHashMap<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (!arg.startsWith("--")) {
				throw new IllegalArgumentException("Unexpected positional argument: " + arg);
			}
			if (i + 1 >= args.length) {
				throw new IllegalArgumentException("Missing value for " + arg);
			}
			opts.put(arg, args[++i]);
		}
		return opts;
	}

	private static String required(Map<String, String> opts, String key) {
		String value = opts.get(key);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		return value;
	}

	private PoiCacheDownloader() {
	}
}
