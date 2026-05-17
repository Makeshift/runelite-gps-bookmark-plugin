package com.gpsbookmark.dumper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Cross-checks the JSON produced by {@link PoiDumper} against the TSV
 * produced by upstream
 * <a href="https://github.com/osrs-pathfinding/shortest-path-tooling">shortest-path-tooling</a>'s
 * {@code BankTileDumperTest} ({@code bankTileDump} Gradle task), to
 * catch regressions where our filters or extraction logic drift from
 * theirs.
 *
 * <p>Opt-in: skipped by default. Enable with:
 * <pre>
 *   ./gradlew test -DcomparePoiDump=true \
 *     -DcomparePoiDump.upstreamTsv=/path/to/bank_tile_placements.tsv \
 *     [-DcomparePoiDump.ourJson=build/generated-resources/poi/com/gpsbookmark/pois/banks.json]
 * </pre>
 * or via the convenience Gradle task:
 * <pre>
 *   ./gradlew comparePoiDumpToUpstream -PupstreamTsv=/path/to/bank_tile_placements.tsv
 * </pre>
 *
 * <p>Generate the upstream TSV by following shortest-path-tooling's
 * instructions (run their {@code bankTileDump} task against the same
 * cache revision used by our build).
 */
public class PoiDumpUpstreamComparisonTest
{
	private static final String ENABLE_PROPERTY = "comparePoiDump";
	private static final String OUR_JSON_PROPERTY = "comparePoiDump.ourJson";
	private static final String UPSTREAM_TSV_PROPERTY = "comparePoiDump.upstreamTsv";
	private static final String DEFAULT_OUR_JSON =
		"build/generated-resources/poi/com/gpsbookmark/pois/banks.json";

	/** How many divergent rows to print per direction before truncating. */
	private static final int MAX_DIFF_ROWS_LOGGED = 25;

	@Test
	public void poiDumpMatchesUpstream() throws IOException
	{
		Assume.assumeTrue(
			"Set -D" + ENABLE_PROPERTY + "=true (and -D" + UPSTREAM_TSV_PROPERTY
				+ "=<tsv>) to enable; see class javadoc.",
			Boolean.getBoolean(ENABLE_PROPERTY));

		Path ourJson = Paths.get(System.getProperty(OUR_JSON_PROPERTY, DEFAULT_OUR_JSON));
		String upstreamTsvProp = System.getProperty(UPSTREAM_TSV_PROPERTY);
		assertTrue("Set -D" + UPSTREAM_TSV_PROPERTY + "=<path-to-upstream-tsv>",
			upstreamTsvProp != null && !upstreamTsvProp.isEmpty());
		Path upstreamTsv = Paths.get(upstreamTsvProp);

		assertTrue("Our JSON does not exist: " + ourJson.toAbsolutePath()
				+ " (run ./gradlew dumpPois first)",
			Files.isRegularFile(ourJson));
		assertTrue("Upstream TSV does not exist: " + upstreamTsv.toAbsolutePath(),
			Files.isRegularFile(upstreamTsv));

		Set<Row> ours = readOurJson(ourJson);
		Set<Row> upstream = readUpstreamTsv(upstreamTsv);

		System.out.println("Our JSON:     " + ours.size() + " row(s) from " + ourJson);
		System.out.println("Upstream TSV: " + upstream.size() + " row(s) from " + upstreamTsv);

		Set<Row> onlyInOurs = new LinkedHashSet<>(ours);
		onlyInOurs.removeAll(upstream);
		Set<Row> onlyInUpstream = new LinkedHashSet<>(upstream);
		onlyInUpstream.removeAll(ours);

		if (onlyInOurs.isEmpty() && onlyInUpstream.isEmpty())
		{
			System.out.println("OK: " + ours.size() + " row(s) match exactly.");
			return;
		}

		StringBuilder sb = new StringBuilder();
		sb.append("POI dump diverges from upstream TSV.\n");
		sb.append("  rows only in our JSON: ").append(onlyInOurs.size()).append('\n');
		sb.append("  rows only in upstream: ").append(onlyInUpstream.size()).append('\n');
		appendSample(sb, "  + (our JSON only)", onlyInOurs);
		appendSample(sb, "  - (upstream only)", onlyInUpstream);
		fail(sb.toString());
	}

	private static void appendSample(StringBuilder sb, String header, Set<Row> rows)
	{
		if (rows.isEmpty())
		{
			return;
		}
		sb.append(header).append(":\n");
		List<Row> sorted = new ArrayList<>(rows);
		Collections.sort(sorted, Row.NATURAL_ORDER);
		int i = 0;
		for (Row r : sorted)
		{
			if (i++ >= MAX_DIFF_ROWS_LOGGED)
			{
				sb.append("    ... ").append(rows.size() - MAX_DIFF_ROWS_LOGGED).append(" more\n");
				break;
			}
			sb.append("    ").append(r).append('\n');
		}
	}

	private static Set<Row> readOurJson(Path path) throws IOException
	{
		try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			JsonElement root = new JsonParser().parse(r);
			JsonArray objects = root.getAsJsonObject().getAsJsonArray("objects");
			Set<Row> out = new TreeSet<>(Row.NATURAL_ORDER);
			for (JsonElement el : objects)
			{
				JsonObject o = el.getAsJsonObject();
				out.add(new Row(
					o.get("id").getAsInt(),
					o.get("x").getAsInt(),
					o.get("y").getAsInt(),
					o.get("plane").getAsInt(),
					o.get("regionId").getAsInt(),
					o.get("localX").getAsInt(),
					o.get("localY").getAsInt(),
					o.get("type").getAsInt(),
					o.get("orientation").getAsInt(),
					o.get("sizeX").getAsInt(),
					o.get("sizeY").getAsInt(),
					optString(o, "name")
				));
			}
			return out;
		}
	}

	private static Set<Row> readUpstreamTsv(Path path) throws IOException
	{
		Set<Row> out = new TreeSet<>(Row.NATURAL_ORDER);
		try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			String header = r.readLine();
			if (header == null)
			{
				throw new IOException("Empty TSV: " + path);
			}
			Map<String, Integer> idx = headerIndex(header);
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.isEmpty())
				{
					continue;
				}
				String[] cols = line.split("\t", -1);
				out.add(new Row(
					tsvInt(cols, idx, "id"),
					tsvInt(cols, idx, "x"),
					tsvInt(cols, idx, "y"),
					tsvInt(cols, idx, "plane"),
					tsvInt(cols, idx, "regionId"),
					tsvInt(cols, idx, "localX"),
					tsvInt(cols, idx, "localY"),
					tsvInt(cols, idx, "type"),
					tsvInt(cols, idx, "orientation"),
					tsvInt(cols, idx, "sizeX"),
					tsvInt(cols, idx, "sizeY"),
					tsvStr(cols, idx, "name")
				));
			}
		}
		return out;
	}

	private static Map<String, Integer> headerIndex(String header)
	{
		String[] cols = header.split("\t", -1);
		Map<String, Integer> idx = new HashMap<>();
		for (int i = 0; i < cols.length; i++)
		{
			idx.put(cols[i], i);
		}
		for (String required : new String[]{
			"id", "name", "x", "y", "plane", "regionId",
			"localX", "localY", "type", "orientation", "sizeX", "sizeY"})
		{
			if (!idx.containsKey(required))
			{
				throw new IllegalArgumentException("Upstream TSV missing column: " + required
					+ " (header was: " + header + ")");
			}
		}
		return idx;
	}

	private static int tsvInt(String[] cols, Map<String, Integer> idx, String name)
	{
		return Integer.parseInt(cols[idx.get(name)]);
	}

	private static String tsvStr(String[] cols, Map<String, Integer> idx, String name)
	{
		return cols[idx.get(name)];
	}

	private static String optString(JsonObject o, String key)
	{
		JsonElement el = o.get(key);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	/**
	 * One placement row, the intersection of upstream's TSV columns and
	 * our JSON's {@code objects[]} fields. {@link #equals} / {@link #hashCode}
	 * use every field so any divergence shows up as a diff.
	 */
	private static final class Row
	{
		static final Comparator<Row> NATURAL_ORDER = Comparator
			.comparingInt((Row r) -> r.regionId)
			.thenComparingInt(r -> r.plane)
			.thenComparingInt(r -> r.x)
			.thenComparingInt(r -> r.y)
			.thenComparingInt(r -> r.id)
			.thenComparingInt(r -> r.type)
			.thenComparingInt(r -> r.orientation);

		final int id;
		final int x;
		final int y;
		final int plane;
		final int regionId;
		final int localX;
		final int localY;
		final int type;
		final int orientation;
		final int sizeX;
		final int sizeY;
		final String name;

		Row(int id, int x, int y, int plane, int regionId, int localX, int localY,
			int type, int orientation, int sizeX, int sizeY, String name)
		{
			this.id = id;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.regionId = regionId;
			this.localX = localX;
			this.localY = localY;
			this.type = type;
			this.orientation = orientation;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.name = name;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (!(o instanceof Row)) return false;
			Row r = (Row) o;
			return id == r.id && x == r.x && y == r.y && plane == r.plane
				&& regionId == r.regionId && localX == r.localX && localY == r.localY
				&& type == r.type && orientation == r.orientation
				&& sizeX == r.sizeX && sizeY == r.sizeY
				&& Objects.equals(name, r.name);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(id, x, y, plane, regionId, localX, localY,
				type, orientation, sizeX, sizeY, name);
		}

		@Override
		public String toString()
		{
			return "id=" + id + " name=" + name + " x=" + x + " y=" + y + " plane=" + plane
				+ " region=" + regionId + " local=" + localX + "," + localY
				+ " type=" + type + " orient=" + orientation
				+ " size=" + sizeX + "x" + sizeY;
		}
	}
}
