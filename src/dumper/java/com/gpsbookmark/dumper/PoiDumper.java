package com.gpsbookmark.dumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * Standalone CLI tool that scans an OSRS cache for object placements
 * matching a {@link PoiDumpConfig} and writes the result as JSON.
 *
 * <p>Modelled after upstream
 * <a href="https://github.com/osrs-pathfinding/shortest-path-tooling/blob/master/src/test/java/shortestpath/dump/BankTileDumperTest.java">BankTileDumperTest</a>,
 * but driven by an external JSON config so the same dumper can be reused
 * for banks, fairy rings, spirit trees, etc.
 *
 * <p>Invocation:
 * <pre>
 *   java -cp ... com.gpsbookmark.dumper.PoiDumper \
 *       --config   path/to/config.json \
 *       --cache    path/to/cache-dir \
 *       --xtea     path/to/keys.json    (runelite format: region/keys) \
 *       --output   path/to/output.json \
 *       [--cache-revision openrs2-id]
 * </pre>
 *
 * <p>Designed to be invoked by the {@code dumpPois} Gradle task; the
 * downloaded cache, transformed XTEA file, and output paths are all
 * managed by Gradle.
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
		String configPath = required(opts, "--config");
		String cacheDir = required(opts, "--cache");
		String xteaPath = required(opts, "--xtea");
		String outputPath = required(opts, "--output");
		String cacheRevision = opts.getOrDefault("--cache-revision", "unknown");

		PoiDumpConfig config = loadConfig(Paths.get(configPath));
		validate(config, configPath);

		Pattern[] namePatterns = compilePatterns(config.namePatterns);
		Set<Integer> forceIncludeIds = new HashSet<>(config.forceIncludeIds);
		Set<Integer> excludeIds = new HashSet<>(config.excludeIds);
		Set<String> requiredActions = lowerCase(config.requiredActions);

		XteaKeyManager xteaKeyManager = new XteaKeyManager();
		try (FileInputStream fin = new FileInputStream(xteaPath))
		{
			xteaKeyManager.loadKeys(fin);
		}

		List<PoiObject> rows;
		Set<Integer> matchingIds;
		try (Store store = new Store(new File(cacheDir)))
		{
			store.load();

			ObjectManager objectManager = new ObjectManager(store);
			objectManager.load();
			RegionLoader regionLoader = new RegionLoader(store, xteaKeyManager);
			regionLoader.loadRegions();
			regionLoader.calculateBounds();

			matchingIds = collectMatchingIds(objectManager, namePatterns, requiredActions,
				forceIncludeIds, excludeIds);
			System.out.println("[" + config.name + "] matched " + matchingIds.size()
				+ " object definition(s)");

			rows = collectPlacements(regionLoader, objectManager, matchingIds);
		}

		Path output = Paths.get(outputPath);
		if (output.getParent() != null)
		{
			Files.createDirectories(output.getParent());
		}
		writeJson(config, cacheRevision, matchingIds.size(), rows, output);
		System.out.println("[" + config.name + "] wrote " + rows.size()
			+ " placement(s) to " + output.toAbsolutePath());
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
			// Gson leaves unset collections null; normalise so callers
			// don't need null guards everywhere.
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
		// Build manually so we can control the top-level shape and pin
		// the schema version at the source rather than relying on a
		// Gson-serialised wrapper class.
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

	/**
	 * One placement of a matched object in the world. Mirrors the columns
	 * of the upstream BankTileDumperTest TSV but in JSON.
	 */
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
