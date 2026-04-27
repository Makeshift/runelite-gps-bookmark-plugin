# GPS Bookmarks

A [RuneLite](https://runelite.net) plugin that lets you bookmark world
locations and quickly send them to the [Shortest Path](https://github.com/Skretzo/shortest-path) plugin to start
navigation.

## Building

```bash
./gradlew jar shadowJar
```

Produced jars can be found in `build/libs/`:

* `gps-bookmark-plugin.jar` (created by the `jar` task) produces the plugin jar by itself.
* `gps-bookmark-plugin-unspecified-all.jar` (created by the `shadowJar` task) produces a fat jar with Runelite and all dependencies shaded in. You can launch a Runelite client from this jar via `java -ea -jar gps-bookmark-plugin-unspecified-all.jar`.

Note that if you use a Jagex account and wish to use the `shadowJar` output to test the plugin, you will need to follow the guidance [in Runelite's wiki](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

Snapshot jars built by CI can be found in the [Actions tab](https://github.com/makeshift/runelite-gps-bookmark-plugin/actions).
