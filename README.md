# GPS Bookmarks

A [RuneLite](https://runelite.net/) external plugin that lets you save in-game world
locations as bookmarks and quickly navigate back to them.

## Features

- Adds a **GPS Bookmarks** sidebar panel.
- Save the player's current world location with a name and free-form notes.
- Hover a bookmark to see its notes.
- Per-bookmark actions:
  - **Navigate** – asks the [Shortest Path](https://github.com/Skretzo/shortest-path)
    plugin to draw a route to the bookmark via cross-plugin communication.
  - **Edit** – open the same form pre-filled with the bookmark's existing values.
  - **Delete** – remove a bookmark (with a confirmation prompt).
- Bookmarks are persisted via RuneLite's `ConfigManager`, so they survive restarts and
  sync with your RuneLite profile.

## Requirements

- RuneLite (Java 11+).
- For the **Navigate** action: install and enable the
  [Shortest Path](https://github.com/Skretzo/shortest-path) plugin from the RuneLite
  Plugin Hub. Without it the navigate request is silently ignored by RuneLite.

## Building

This is a standard RuneLite plugin Gradle project:

```sh
./gradlew shadowJar
```

The jar is produced at `build/libs/gps-bookmark-plugin-<version>-all.jar` and can be
side-loaded into RuneLite developer mode via `--external-plugins`.

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the same jar on every
push and pull request and uploads it as a workflow artifact.

## Cross-plugin communication

The Navigate action posts a `PluginMessage` on the RuneLite event bus targeted at the
`shortestpath` namespace, as documented on the
[Shortest Path wiki](https://github.com/Skretzo/shortest-path/wiki/Cross-plugin-communication).
