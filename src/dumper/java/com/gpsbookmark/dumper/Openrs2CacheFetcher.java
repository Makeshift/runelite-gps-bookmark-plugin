package com.gpsbookmark.dumper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves and downloads OSRS cache snapshots from
 * <a href="https://archive.openrs2.org">archive.openrs2.org</a>, writing
 * each snapshot to {@code <cacheRoot>/<id>/{cache/, keys.json}} with a
 * {@code .downloaded} marker so subsequent calls reuse it.
 *
 * <p>The downloaded {@code keys.json} is rewritten from openrs2's
 * {@code mapsquare}/{@code key} shape into the {@code region}/{@code keys}
 * shape expected by {@link net.runelite.cache.util.XteaKeyManager}.
 */
final class Openrs2CacheFetcher
{
	private static final String CACHES_INDEX_URL = "https://archive.openrs2.org/caches.json";
	private static final String CACHE_BASE_URL = "https://archive.openrs2.org/caches/runescape/";
	private static final int CONNECT_TIMEOUT_MS = 30_000;
	private static final int READ_TIMEOUT_MS = 120_000;

	private static final Gson GSON = new Gson();

	private Openrs2CacheFetcher()
	{
	}

	/**
	 * Returns the id of the newest oldschool/live cache on openrs2 with
	 * a valid disk store and at least one valid XTEA key.
	 */
	static String resolveLatestCacheId() throws IOException
	{
		System.out.println("Querying " + CACHES_INDEX_URL + " for latest OSRS cache...");
		JsonArray caches;
		try (InputStream in = openStream(CACHES_INDEX_URL);
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			caches = JsonParser.parseReader(reader).getAsJsonArray();
		}

		List<JsonObject> candidates = new ArrayList<>();
		for (JsonElement el : caches)
		{
			JsonObject o = el.getAsJsonObject();
			if (!"oldschool".equals(asString(o, "game"))
				|| !"live".equals(asString(o, "environment")))
			{
				continue;
			}
			if (!asBoolean(o, "disk_store_valid")
				|| asInt(o, "valid_keys", 0) <= 0
				|| asString(o, "timestamp") == null)
			{
				continue;
			}
			candidates.add(o);
		}
		if (candidates.isEmpty())
		{
			throw new IOException("No suitable oldschool/live cache found on openrs2.");
		}
		candidates.sort(Comparator.comparing((JsonObject o) -> asString(o, "timestamp")).reversed());
		JsonObject chosen = candidates.get(0);
		String id = String.valueOf(chosen.get("id").getAsInt());
		System.out.println("Selected openrs2 cache id " + id
			+ " (timestamp " + asString(chosen, "timestamp") + ")");
		return id;
	}

	/**
	 * Ensures {@code <targetDir>/cache/} and {@code <targetDir>/keys.json}
	 * exist for the given cache id, downloading and extracting from
	 * openrs2 if the marker file is absent. Subsequent calls with the
	 * same id are a no-op.
	 */
	static void ensureDownloaded(String cacheId, Path targetDir) throws IOException
	{
		Path cacheDir = targetDir.resolve("cache");
		Path keysFile = targetDir.resolve("keys.json");
		Path marker = targetDir.resolve(".downloaded");

		if (Files.isRegularFile(marker)
			&& Files.isDirectory(cacheDir)
			&& Files.isRegularFile(keysFile))
		{
			System.out.println("Reusing cached openrs2 cache " + cacheId + " at " + targetDir);
			return;
		}

		Files.createDirectories(cacheDir);

		downloadAndExtractDiskStore(cacheId, targetDir, cacheDir);
		downloadAndTransformKeys(cacheId, keysFile);

		Files.write(marker,
			("openrs2 cache id: " + cacheId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		System.out.println("Cache + keys ready at " + targetDir);
	}

	private static void downloadAndExtractDiskStore(String cacheId, Path targetDir, Path cacheDir)
		throws IOException
	{
		Path diskZip = targetDir.resolve("disk.zip");
		String url = CACHE_BASE_URL + cacheId + "/disk.zip";
		System.out.println("Downloading " + url + " (~170MB)...");
		try (InputStream in = openStream(url))
		{
			Files.copy(in, diskZip, StandardCopyOption.REPLACE_EXISTING);
		}

		System.out.println("Extracting disk store to " + cacheDir + "...");
		try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(diskZip)))
		{
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null)
			{
				// openrs2 zips wrap files in a top-level "cache/" dir;
				// flatten so RuneLite's Store can find main_file_cache.*
				// directly under cacheDir.
				String name = entry.getName().replace('\\', '/');
				int slash = name.indexOf('/');
				String flat = slash >= 0 ? name.substring(slash + 1) : name;
				if (flat.isEmpty())
				{
					continue;
				}
				Path out = cacheDir.resolve(flat).normalize();
				if (!out.startsWith(cacheDir))
				{
					// Defend against zip slip even though the source is trusted.
					throw new IOException("Refusing to extract entry outside cache dir: " + entry.getName());
				}
				if (entry.isDirectory())
				{
					Files.createDirectories(out);
					continue;
				}
				if (out.getParent() != null)
				{
					Files.createDirectories(out.getParent());
				}
				Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Files.deleteIfExists(diskZip);
	}

	private static void downloadAndTransformKeys(String cacheId, Path keysFile) throws IOException
	{
		String keysUrl = CACHE_BASE_URL + cacheId + "/keys.json";
		System.out.println("Downloading " + keysUrl + "...");
		JsonArray rawKeys;
		try (InputStream in = openStream(keysUrl);
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			rawKeys = JsonParser.parseReader(reader).getAsJsonArray();
		}

		// openrs2 format uses {mapsquare, key}; RuneLite's XteaKeyManager
		// wants {region, keys}. Translate in place.
		JsonArray transformed = new JsonArray(rawKeys.size());
		for (JsonElement el : rawKeys)
		{
			JsonObject src = el.getAsJsonObject();
			JsonObject dst = new JsonObject();
			dst.add("region", src.get("mapsquare"));
			dst.add("keys", src.get("key"));
			transformed.add(dst);
		}

		try (BufferedWriter w = Files.newBufferedWriter(keysFile, StandardCharsets.UTF_8))
		{
			GSON.toJson(transformed, w);
		}
	}

	private static InputStream openStream(String url) throws IOException
	{
		URLConnection conn = new URL(url).openConnection();
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		return conn.getInputStream();
	}

	private static String asString(JsonObject o, String k)
	{
		JsonElement el = o.get(k);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	private static boolean asBoolean(JsonObject o, String k)
	{
		JsonElement el = o.get(k);
		return el != null && !el.isJsonNull() && el.getAsBoolean();
	}

	private static int asInt(JsonObject o, String k, int dflt)
	{
		JsonElement el = o.get(k);
		return el == null || el.isJsonNull() ? dflt : el.getAsInt();
	}

}
