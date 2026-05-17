package com.gpsbookmark.dumper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Scans an OSRS cache for object placements matching one or more
 * {@link PoiDumpConfig}s and writes each result as a JSON file under
 * {@code --output-dir}.
 *
 * <p>
 * Invocation:
 *
 * <pre>
 *   java -cp ... com.gpsbookmark.dumper.PoiDumper \
 *       --config-dir       poi-configs/ \
 *       --cache-root       .poi-cache/ \
 *       --output-dir       build/generated-resources/poi/ \
 *       --cache-id         2499 \
 *       [--skip]
 * </pre>
 *
 * <p>
 * The cache must already exist under {@code <cache-root>/<id>/}, usually
 * via the {@code downloadPoiCache} Gradle task. {@code --skip} writes
 * nothing and exits 0.
 */
public final class PoiDumper
{
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();

	public static void main(String[] args) throws IOException
	{
		Map<String, String> opts = parseArgs(args);

		if (opts.containsKey("--skip"))
		{
			System.out.println("POI dump skipped (--skip); no resources generated.");
			return;
		}

		Path outputDir = Paths.get(required(opts, "--output-dir"));
		Files.createDirectories(outputDir);

		Path configDir = Paths.get(required(opts, "--config-dir"));
		Path cacheRoot = Paths.get(required(opts, "--cache-root"));
		String cacheId = required(opts, "--cache-id");

		List<Path> configFiles = listConfigs(configDir);

		Path cacheHome = cacheRoot.resolve(cacheId);
		File cacheDir = cacheHome.resolve("cache").toFile();
		File xteaFile = cacheHome.resolve("keys.json").toFile();
		ensureCachePresent(cacheId, cacheHome, cacheDir, xteaFile);

		XteaKeyManager xteaKeyManager = new XteaKeyManager();
		try (FileInputStream fin = new FileInputStream(xteaFile))
		{
			xteaKeyManager.loadKeys(fin);
		}

		try (Store store = new Store(cacheDir))
		{
			store.load();

			ObjectManager objectManager = new ObjectManager(store);
			objectManager.load();
			RegionLoader regionLoader = new RegionLoader(store, xteaKeyManager);
			regionLoader.loadRegions();
			regionLoader.calculateBounds();

			for (Path cfgPath : configFiles)
			{
				dumpOne(cfgPath, objectManager, regionLoader, outputDir, cacheId);
			}
		}
	}

	private static void ensureCachePresent(String cacheId, Path cacheHome, File cacheDir, File xteaFile)
			throws IOException {
		if (cacheDir.isDirectory() && xteaFile.isFile()) {
			return;
		}

		throw new IOException(
				"Cache " + cacheId + " is not available at " + cacheHome + ". " +
						"Run ./gradlew downloadPoiCache -PpoiCacheId=" + cacheId +
						" first, or choose a downloaded cache id.");
	}

	private static void dumpOne(Path cfgPath, ObjectManager objectManager,
		RegionLoader regionLoader, Path outputDir, String cacheRevision) throws IOException
	{
		PoiDumpConfig config = loadConfig(cfgPath);
		validate(config, cfgPath.toString());

		Pattern[] namePatterns = compilePatterns(config.namePatterns);
		Set<Integer> forceIncludeIds = new HashSet<>(config.forceIncludeIds);
		Set<Integer> excludeIds = new HashSet<>(config.excludeIds);
		Set<String> requiredActions = lowerCase(config.requiredActions);

		Set<Integer> matchingIds = collectMatchingIds(objectManager, namePatterns, requiredActions,
			forceIncludeIds, excludeIds);
		System.out.println("[" + config.name + "] matched " + matchingIds.size()
			+ " object definition(s)");

		List<PoiObject> rows = collectPlacements(regionLoader, objectManager, matchingIds);

		Path output = outputDir.resolve(config.outputResourcePath);
		if (output.getParent() != null)
		{
			Files.createDirectories(output.getParent());
		}
		writeJson(config, cacheRevision, matchingIds.size(), rows, output);
		System.out.println("[" + config.name + "] wrote " + rows.size()
			+ " placement(s) to " + output.toAbsolutePath());
	}

	private static List<Path> listConfigs(Path configDir) throws IOException
	{
		if (!Files.isDirectory(configDir))
		{
			throw new IOException("Config dir does not exist: " + configDir);
		}
		List<Path> configs = new ArrayList<>();
		try (java.util.stream.Stream<Path> stream = Files.list(configDir))
		{
			stream
				.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted()
				.forEach(configs::add);
		}
		if (configs.isEmpty())
		{
			throw new IOException("No POI configs (*.json) found in " + configDir);
		}
		return configs;
	}

	// --- Config & arg parsing -------------------------------------------

	private static PoiDumpConfig loadConfig(Path path) throws IOException
	{
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			PoiDumpConfig config = GSON.fromJson(reader, PoiDumpConfig.class);
			if (config == null)
			{
				throw new IOException("Empty or invalid config: " + path);
			}
			// Gson leaves unset collections null; normalise.
			if (config.namePatterns == null) config.namePatterns = new ArrayList<>();
			if (config.requiredActions == null) config.requiredActions = new ArrayList<>();
			if (config.forceIncludeIds == null) config.forceIncludeIds = new ArrayList<>();
			if (config.excludeIds == null) config.excludeIds = new ArrayList<>();
			return config;
		}
	}

	private static void validate(PoiDumpConfig config, String configPath)
	{
		if (config.name == null || config.name.isEmpty())
		{
			throw new IllegalArgumentException("Config " + configPath + " is missing required field 'name'");
		}
		if (config.outputResourcePath == null || config.outputResourcePath.isEmpty())
		{
			throw new IllegalArgumentException("Config " + configPath + " is missing required field 'outputResourcePath'");
		}
		if (config.namePatterns.isEmpty() && config.forceIncludeIds.isEmpty())
		{
			throw new IllegalArgumentException("Config " + configPath
				+ " must specify at least one of 'namePatterns' or 'forceIncludeIds'");
		}
	}

	private static Map<String, String> parseArgs(String[] args)
	{
		Map<String, String> opts = new LinkedHashMap<>();
		for (int i = 0; i < args.length; i++)
		{
			String a = args[i];
			if (!a.startsWith("--"))
			{
				throw new IllegalArgumentException("Unexpected positional argument: " + a);
			}
			// Valueless flags.
			if ("--skip".equals(a))
			{
				opts.put(a, "true");
				continue;
			}
			if (i + 1 >= args.length)
			{
				throw new IllegalArgumentException("Missing value for " + a);
			}
			opts.put(a, args[++i]);
		}
		return opts;
	}

	private static String required(Map<String, String> opts, String key)
	{
		String v = opts.get(key);
		if (v == null || v.isEmpty())
		{
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		return v;
	}

	private static Pattern[] compilePatterns(List<String> patterns)
	{
		Pattern[] result = new Pattern[patterns.size()];
		for (int i = 0; i < patterns.size(); i++)
		{
			result[i] = Pattern.compile(patterns.get(i));
		}
		return result;
	}

	private static Set<String> lowerCase(List<String> in)
	{
		Set<String> out = new HashSet<>(in.size());
		for (String s : in)
		{
			if (s != null) out.add(s.toLowerCase(java.util.Locale.ROOT));
		}
		return out;
	}

	// --- Cache scanning -------------------------------------------------

	private static Set<Integer> collectMatchingIds(ObjectManager objectManager, Pattern[] patterns,
		Set<String> requiredActions, Set<Integer> forceIncludeIds, Set<Integer> excludeIds)
	{
		Set<Integer> ids = new HashSet<>();
		for (ObjectDefinition def : objectManager.getObjects())
		{
			int id = def.getId();
			if (excludeIds.contains(id))
			{
				continue;
			}
			if (forceIncludeIds.contains(id))
			{
				ids.add(id);
				continue;
			}
			String name = def.getName();
			if (name == null || "null".equalsIgnoreCase(name))
			{
				continue;
			}
			boolean nameMatch = false;
			for (Pattern p : patterns)
			{
				if (p.matcher(name).matches())
				{
					nameMatch = true;
					break;
				}
			}
			if (!nameMatch)
			{
				continue;
			}
			if (!requiredActions.isEmpty() && !hasAnyAction(def, requiredActions))
			{
				continue;
			}
			ids.add(id);
		}
		return ids;
	}

	private static boolean hasAnyAction(ObjectDefinition def, Set<String> requiredActions)
	{
		EntityOpsDefinition ops = def.getOps();
		if (ops == null || ops.ops == null)
		{
			return false;
		}
		for (EntityOpsDefinition.Op op : ops.ops)
		{
			if (op == null || op.text == null)
			{
				continue;
			}
			if (requiredActions.contains(op.text.toLowerCase(java.util.Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
	}

	private static List<PoiObject> collectPlacements(RegionLoader regionLoader,
		ObjectManager objectManager, Set<Integer> matchingIds)
	{
		List<PoiObject> rows = new ArrayList<>();
		Collection<Region> regions = regionLoader.getRegions();
		for (Region region : regions)
		{
			for (Location loc : region.getLocations())
			{
				int id = loc.getId();
				if (!matchingIds.contains(id))
				{
					continue;
				}
				ObjectDefinition def = objectManager.getObject(id);
				Position pos = loc.getPosition();
				PoiObject row = new PoiObject();
				row.id = id;
				row.name = def.getName();
				row.x = pos.getX();
				row.y = pos.getY();
				row.plane = pos.getZ();
				row.regionId = region.getRegionID();
				row.localX = pos.getX() - region.getBaseX();
				row.localY = pos.getY() - region.getBaseY();
				row.type = loc.getType();
				row.orientation = loc.getOrientation();
				row.sizeX = def.getSizeX();
				row.sizeY = def.getSizeY();
				rows.add(row);
			}
		}
		Collections.sort(rows, (a, b) ->
		{
			int c = Integer.compare(a.regionId, b.regionId);
			if (c != 0) return c;
			c = Integer.compare(a.plane, b.plane);
			if (c != 0) return c;
			c = Integer.compare(a.x, b.x);
			if (c != 0) return c;
			return Integer.compare(a.y, b.y);
		});
		return rows;
	}

	// --- Output ---------------------------------------------------------

	private static void writeJson(PoiDumpConfig config, String cacheRevision, int matchedDefs,
		List<PoiObject> rows, Path output) throws IOException
	{
		PoiDumpResult result = new PoiDumpResult();
		result.name = config.name;
		result.schemaVersion = 1;
		result.generatedAt = Instant.now().toString();
		result.cacheRevision = cacheRevision;
		result.matchedDefinitions = matchedDefs;
		result.namePatterns = new ArrayList<>(config.namePatterns);
		result.requiredActions = new ArrayList<>(config.requiredActions);
		result.forceIncludeIds = new ArrayList<>(config.forceIncludeIds);
		result.excludeIds = new ArrayList<>(config.excludeIds);
		result.objects = rows;

		try (BufferedWriter w = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			GSON.toJson(result, PoiDumpResult.class, w);
		}
	}

	/** Top-level shape of the JSON written to disk. */
	@SuppressWarnings("unused") // fields read reflectively by Gson
	private static final class PoiDumpResult
	{
		String name;
		int schemaVersion;
		String generatedAt;
		String cacheRevision;
		int matchedDefinitions;
		List<String> namePatterns;
		List<String> requiredActions;
		List<Integer> forceIncludeIds;
		List<Integer> excludeIds;
		List<PoiObject> objects;
	}

	/** One placement of a matched object in the world. */
	@SuppressWarnings("unused") // fields read reflectively by Gson
	static final class PoiObject
	{
		int id;
		String name;
		int x;
		int y;
		int plane;
		int regionId;
		int localX;
		int localY;
		int type;
		int orientation;
		int sizeX;
		int sizeY;
	}

	private PoiDumper()
	{
	}
}
