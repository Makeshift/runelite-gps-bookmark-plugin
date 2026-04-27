package com.gpsbookmark.dumper;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON-deserialized configuration for a single POI dump.
 *
 * <p>One config file produces one JSON output containing every object
 * placement in the OSRS cache that matches the configured name patterns
 * (and, optionally, exposes one of the configured menu actions). It is
 * the runtime analogue of the upstream
 * {@code shortest-path-tooling/BankTileDumperTest} — see that file for
 * the original design — but generalised to any POI category.
 */
public class PoiDumpConfig
{
	/** Human-readable identifier (used in logs and embedded in the output). */
	public String name;

	/**
	 * Resource path (relative to the plugin's resources root) where the
	 * generated JSON should be written. e.g. {@code com/gpsbookmark/pois/banks.json}.
	 */
	public String outputResourcePath;

	/**
	 * Regex patterns matched (case-sensitively unless the pattern itself
	 * uses {@code (?i)}) against the cache object definition's name. An
	 * object is considered for inclusion if any pattern matches.
	 */
	public List<String> namePatterns = new ArrayList<>();

	/**
	 * If non-empty, an object that matched a name pattern must additionally
	 * expose at least one menu op whose text equals (case-insensitively)
	 * one of these actions. Mirrors the {@code hasBankAction} filter in the
	 * upstream BankTileDumperTest, which is required to distinguish e.g.
	 * decorative "Bank table" scenery from interactive Varlamore bank tables.
	 */
	public List<String> requiredActions = new ArrayList<>();

	/**
	 * Object IDs always included regardless of name or action filter. Use
	 * for interactive objects whose cache name is generic (e.g.
	 * Culinaromancer's Chest is named "Chest") but which genuinely act as
	 * the POI in-game.
	 */
	public List<Integer> forceIncludeIds = new ArrayList<>();

	/** Object IDs to exclude even if they would otherwise match. */
	public List<Integer> excludeIds = new ArrayList<>();
}
