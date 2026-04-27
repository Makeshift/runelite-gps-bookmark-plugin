# GPS Bookmarks

A [RuneLite](https://runelite.net) plugin that lets you bookmark world
locations and quickly send them to the
[Shortest Path](https://github.com/Skretzo/shortest-path) plugin to start
navigation.

## Features

* A sidebar panel with an **Add** button to create new bookmarks.
* Bookmarks store an X / Y / Plane (a
  [`WorldPoint`](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/coords/WorldPoint.html)),
  a **name** and free-form **notes**.
* When adding a new bookmark, the X / Y / Plane fields are pre-filled with the
  player's current world location. A **Use current location** button is
  available to refresh them at any time.
* Each saved bookmark is shown in the sidebar with its name; hover the row to
  see the notes (and exact coordinates) in a tooltip.
* Each bookmark has three actions:
  * **Navigate** – sends a `PluginMessage` to the Shortest Path plugin to draw a
    path from the player's current location to the bookmark.
  * **Edit** – re-opens the editor pre-filled with the bookmark's data.
  * **Delete** – removes the bookmark (with a confirmation prompt).
* Bookmarks are persisted across sessions via the RuneLite plugin config
  (serialized as JSON under a hidden config key).

## Cross-plugin communication

The **Navigate** action follows the
[Shortest Path cross-plugin communication](https://github.com/Skretzo/shortest-path/wiki/Cross-plugin-communication)
contract: it posts a `PluginMessage` with namespace `shortestpath` and name
`path`, with the bookmark's `WorldPoint` set as the `target` entry. The
`start` entry is omitted so Shortest Path defaults to the player's current
location.

## Building

```
./gradlew jar shadowJar
```

The CI workflow at `.github/workflows/build.yml` builds both targets on every
push and pull request and uploads them together as build artifacts.
