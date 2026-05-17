package com.gpsbookmark;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Loads point-of-interest data (e.g. bank tile placements) bundled with
 * the plugin jar at build time by the {@code dumpPois} Gradle task.
 *
 * <p>The on-disk JSON schema mirrors {@code com.gpsbookmark.dumper.PoiDumper.PoiDumpResult};
 * we only need the {@code objects[]} entries here.</p>
 */
@Slf4j
final class PoiCatalog
{
	/** Key used by the sidebar's "find closest" dropdown. */
	static final String BANKS = "Bank";
	static final String FAIRY_RINGS = "Fairy Ring";
	static final String SPIRIT_TREES = "Spirit Tree";
	static final String OBELISKS = "Obelisk";
	static final String LOVAKENGJ_MINECARTS = "Lovakengj Minecart";
	static final String QUETZALS = "Quetzal";
	static final String GNOME_GLIDERS = "Gnome Glider";

	private static final Map<String, String> RESOURCES;
	static
	{
		final Map<String, String> m = new LinkedHashMap<>();
		m.put(BANKS, "/com/gpsbookmark/pois/banks.json");
		m.put(FAIRY_RINGS, "/com/gpsbookmark/pois/fairy-rings.json");
		m.put(SPIRIT_TREES, "/com/gpsbookmark/pois/spirit-trees.json");
		m.put(OBELISKS, "/com/gpsbookmark/pois/obelisks.json");
		m.put(LOVAKENGJ_MINECARTS, "/com/gpsbookmark/pois/lovakengj-minecarts.json");
		m.put(QUETZALS, "/com/gpsbookmark/pois/quetzals.json");
		m.put(GNOME_GLIDERS, "/com/gpsbookmark/pois/gnome-gliders.json");
		RESOURCES = Collections.unmodifiableMap(m);
	}

	private final Gson gson;
	private final Map<String, List<WorldPoint>> cache = new LinkedHashMap<>();

	PoiCatalog(Gson gson)
	{
		this.gson = gson;
	}

	/** Names of all POI categories the catalog can navigate to, in display order. */
	List<String> categories()
	{
		return new java.util.ArrayList<>(RESOURCES.keySet());
	}

	/**
	 * Returns the world points for the given category, lazily parsing the
	 * embedded JSON the first time it is requested. Never returns {@code null};
	 * returns an empty list if the resource is missing or unparseable.
	 */
	List<WorldPoint> getPoints(String category)
	{
		List<WorldPoint> cached = cache.get(category);
		if (cached != null)
		{
			return cached;
		}

		final String resource = RESOURCES.get(category);
		if (resource == null)
		{
			return Collections.emptyList();
		}

		List<WorldPoint> points = loadPoints(resource);
		cache.put(category, points);
		return points;
	}

	private List<WorldPoint> loadPoints(String resource)
	{
		try (InputStream in = getClass().getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.warn("POI resource {} is not bundled with the plugin jar; "
					+ "rebuild with the POI dumper enabled to populate it.", resource);
				return Collections.emptyList();
			}

			final PoiFile parsed = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), PoiFile.class);
			if (parsed == null || parsed.objects == null)
			{
				return Collections.emptyList();
			}

			final java.util.ArrayList<WorldPoint> out = new java.util.ArrayList<>(parsed.objects.size());
			for (PoiEntry e : parsed.objects)
			{
				out.add(new WorldPoint(e.x, e.y, e.plane));
			}
			return Collections.unmodifiableList(out);
		}
		catch (JsonSyntaxException | IOException ex)
		{
			log.warn("Failed to read POI resource {}", resource, ex);
			return Collections.emptyList();
		}
	}

	/** Minimal mirror of the dumper output schema (we only need the placements). */
	private static final class PoiFile
	{
		List<PoiEntry> objects;
	}

	private static final class PoiEntry
	{
		int x;
		int y;
		int plane;
	}
}
