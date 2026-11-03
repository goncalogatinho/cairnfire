<h1 align="center">
  <img src="assets/banner.png" alt="Cairnfire" width="720">
</h1>

**Keep your game saves identical on your Windows PC and your Mac, with no server and no copying files by hand.**

Written in [Kotlin](https://kotlinlang.org/)

---

## What is this, in a few words

You play on your PC in the morning and want to carry on in the evening on your Mac? Normally you have to copy the
save files from one machine to the other, and forgetting just once can cost you hours of progress.

Cairnfire does that for you. You tell it where each game keeps its saves on each computer, and it uses a folder in
your cloud (Google Drive, Dropbox, OneDrive…) as the meeting point:

```
  Windows PC                    Cloud                        Mac
 ┌───────────┐    pushes   ┌───────────────┐    pulls    ┌───────────┐
 │  saves A  │ ──────────► │ shared folder │ ◄────────── │  saves B  │
 └───────────┘ ◄────────── └───────────────┘ ──────────► └───────────┘
                  pulls                        pushes
```

When you finish playing on one computer, Cairnfire pushes the new save to the cloud. When you open the other one,
it pulls it down. If anything goes wrong, the old version is kept in a backup.

> **Important:** Cairnfire is not a cloud. Your Drive/Dropbox/OneDrive is what carries the files from one machine
> to the other; Cairnfire only decides *what* to copy and keeps you from losing saves along the way.

## How it decides what to copy

The simplest idea would be "the newest file wins", but that loses saves when both computers change different
things. So Cairnfire keeps a "snapshot" (a SHA-256 hash) of every file **as of the last time it synced** and
compares three versions: the local one, the cloud one and that snapshot.

| Local | Cloud | What happens |
|-------|-------|--------------|
| changed | same as last time | pushed to the cloud |
| same as last time | changed | pulled from the cloud |
| changed | changed | **conflict**: the newest is kept and the other goes to backup |
| new file | does not exist | pushed to the cloud |
| deleted | same as last time | also deleted in the cloud (with a backup) |
| deleted | changed | **not deleted**: the changed file is kept |

## How Cairnfire protects your saves

- **Backup before anything.** Any file that is about to be replaced or deleted is first copied to
  `~/.cairnfire/backups/<game>/<date-time>/`.
- **Crash-safe copies.** It copies to a temporary file and only then moves it into place. If the computer shuts
  down halfway, you never end up with a half-written save.
- **Cloud not mounted = does nothing.** If the cloud folder does not exist (for example, Drive has not started
  yet), the program stops instead of assuming "everything was deleted".
- **Missing folder = game skipped.** If a folder that was already synced stops existing (wrong path, unplugged
  disk), that game is skipped instead of wiping everything on the other side.
- **Waits for the game to finish writing.** In automatic mode it only syncs after the folder has been quiet for a
  few seconds.
- **Ignores system junk:** `.DS_Store`, `Thumbs.db`, `desktop.ini`.

## Installation

### What you need

- A **JDK 17 to 21** (for example [Temurin](https://adoptium.net/) 21) to build it. Gradle 8.14 and Kotlin 2.1
  do not work with JDK 25 or newer yet.
  On macOS, if you have several JDKs: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- A cloud folder that is synced on both computers (Google Drive, Dropbox, OneDrive…).

### Build

```bash
git clone https://github.com/goncalogatinho/cairnfire.git
cd cairnfire
./gradlew installDist          # on Windows: gradlew.bat installDist
```

The executable ends up in `build/install/cairnfire/bin/cairnfire` (`cairnfire.bat` on Windows). To use it from
anywhere, add that `bin` folder to your `PATH`.

### Version without Java installed (optional)

```bash
./gradlew jpackage
```

This produces, in `build/jpackage/`, an app with a minimal Java inside (about 90 MB, because of the window), so
the machine you run it on does not need Java:

- **macOS:** `build/jpackage/cairnfire.app` (executable at `cairnfire.app/Contents/MacOS/cairnfire`)
- **Windows:** `build\jpackage\cairnfire\cairnfire.exe` (command line) and `cairnfire-gui.exe` (opens the window, no console)

`jpackage` does not cross-compile: run the command on each system to build that system's version. The app is not
signed, so if you copy it from another computer macOS may block it; the simplest thing is to build on each machine.

## The window

If you prefer clicking to typing commands, Cairnfire also has a simple window:

```bash
cairnfire gui
```

<p align="center">
  <img src="assets/screenshot.png" alt="The Cairnfire window" width="640">
</p>

- **Cloud folder** and **list of games**, each with its status: in sync, N changes (orange, or red when there is a
  conflict). Click a game to see exactly what is going to be copied.
- **Sync** one game or **Sync all**. Before anything is copied, the window shows what is going to happen.
- **Add / Edit / Remove** games. Remove only takes the game off the list: it never deletes saves.
- **Automatic** turns on `watch` mode inside the window (only while it is open). To also sync with the window
  closed, use `autostart`.
- **Activity** shows what was done, with errors in red.

The window uses the same engine as the command line, so the protections (backups, safe copies, cloud not mounted)
are exactly the same. In the packaged app (`jpackage`), opening it with no arguments, for example by
double-clicking, opens the window.

## Getting started

**1. On each computer, once:** tell it which folder is your cloud folder (each machine has its own path, but it is
the same synced folder).

```bash
cairnfire init --sync-root "~/Google Drive/Cairnfire"          # Mac
cairnfire init --sync-root "G:/My Drive/Cairnfire"             # Windows
```

**2. Register your games.** You can give the paths for both systems at once (or just the one for the machine you
are on and fill in the other later). Example with Stardew Valley:

```bash
cairnfire add "Stardew Valley" \
  --windows "%APPDATA%/StardewValley/Saves" \
  --mac "~/.config/StardewValley/Saves"
```

Paths accept `~`, `%VARIABLE%` and `${VARIABLE}`. The game name becomes the folder name in the cloud.

**3. See what would happen, then sync:**

```bash
cairnfire status               # only shows, changes nothing
cairnfire sync                 # syncs every game
cairnfire sync "Stardew Valley" --dry-run
```

Example output:

```
[Stardew Valley] done:
    local  -> cloud  Farm_123456/Farm_123456
 !  cloud  -> local  Farm_123456/SaveGameInfo  (changed on both sides; cloud is newer)
    (replaced or deleted versions are kept in ~/.cairnfire/backups/Stardew Valley)
```

Lines marked with `!` are conflicts: the newest won and the other version is in the backup.

## All commands

| Command | What it does |
|---|---|
| `init --sync-root <folder>` | Sets the cloud folder on this machine |
| `add <game> --windows <folder> --mac <folder>` | Registers a game (or updates the paths of one already registered) |
| `list` | Lists the registered games |
| `status [game]` | Shows what would be copied, changes nothing |
| `sync [game] [--dry-run]` | Syncs (one game or all of them) |
| `watch [--interval 60] [--debounce 5]` | Keeps running and syncs by itself |
| `gui` | Opens the window |
| `autostart install` / `uninstall` / `status` | Makes `watch` start at login |

## Automatic mode

Instead of running `sync` by hand, leave `watch` running:

```bash
cairnfire watch
```

It syncs on startup and then again whenever:

- something changes in the saves folder or in the game's folder in the cloud, after `--debounce` seconds without
  further changes (so a save is not copied while the game is still writing it);
- `--interval` seconds pass, even without events. This is needed because cloud clients do not always generate
  file events, and on macOS Java only checks for changes every ~10 seconds.

It only prints something when there are files to copy, and if the cloud is not mounted it tries again on the next
cycle.

### Start automatically at login

```bash
cairnfire autostart install
cairnfire autostart status
cairnfire autostart uninstall
```

- **macOS:** creates an agent at `~/Library/LaunchAgents/com.cairnfire.watch.plist` (launchd). It starts at login
  and restarts if it fails.
- **Windows:** puts a `cairnfire-watch.vbs` shortcut in the Startup folder, which runs `watch` without a window.

The log is at `~/.cairnfire/watch.log`. Autostart stores the path of the executable and the Java used at
`install` time, so if you move the app to another folder, run `autostart install` again.

## Where Cairnfire keeps its files

Everything lives in `~/.cairnfire/` (or in the folder named by the `CAIRNFIRE_HOME` variable):

```
~/.cairnfire/
├── config.json        cloud folder and registered games
├── state/<game>.json  "snapshot" of the last sync (hashes)
├── backups/<game>/    replaced or deleted versions
└── watch.log          log of the automatic mode
```

## How the code is organised

```
assets/                      logo, banners, app icons and screenshot (plain image files)
src/main/kotlin/cairnfire/
├── Main.kt        command-line commands (Clikt)
├── Sync.kt        syncing one game (shared by the CLI and the window)
├── SyncEngine.kt  the brain: decides what to copy (3-way merge), makes backups, computes the new state
├── Files.kt       SHA-256 hashes, folder listing, path expansion, safe copy
├── Config.kt      reading and writing the configuration and state as JSON
├── Watch.kt       watches folders with Java's WatchService
├── Autostart.kt   autostart (launchd on macOS, Startup folder on Windows)
└── gui/           the window (Compose Desktop): theme, pixel components, screens and state
src/test/kotlin/cairnfire/   automated tests
```

Technologies: Kotlin/JVM, [Gradle](https://gradle.org/), [Clikt](https://ajalt.github.io/clikt/) (command line),
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON),
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) (the window).

## Tests

```bash
./gradlew test
```

They cover the sync engine (push, pull, conflicts, deletions, `--dry-run`), the folder watcher, the generation of
the autostart files and the window's state logic. The watcher tests can take a few seconds on macOS.

## Project status and limitations

Cairnfire is under development and is a study project. Worth knowing:

- **It has been tested on macOS.** The Windows code (paths, `jpackage` and autostart) exists but has not been run
  on a real Windows machine yet.
- **Conflicts are resolved by modification time.** If the computers' clocks are out of sync, the "newest" may not
  be the right one. That is what the backups are for.
- **It depends on your cloud client.** If Drive/Dropbox has not downloaded the latest save yet, Cairnfire only
  sees the old version. Wait for the cloud to finish syncing before opening the game on the other side.
- **The window is new and simple:** it was tested on macOS (only startup and rendering, not every click). Autostart
  does not work on Linux.
- Always make a copy of your saves before trying it for the first time on an important game.

## Ideas for the future

- Installer (`.dmg` / `.msi`) and app signing
- More from the window: resolve conflicts by picking a version, browse and restore backups
- Detecting out-of-sync clocks and clearer warnings on conflicts
- Autostart on Linux (systemd)

## Logo

The symbol is a *cairn* (those piles of stones that mark a trail) with the top stone on fire: the flame lights up
the stones and the title. It is drawn in pixel art, and all the images live in [`assets/`](assets/) as plain files:

| File | What it is |
|---|---|
| `banner.png` | The banner at the top of this README |
| `logo.png` | The symbol on its own (512×512) |
| `banner-ui.png` | The banner shown inside the app window (with room for the glow to fade out) |
| `icon-1024.png` | The master app icon (1024×1024, rounded square) |
| `icon.icns` / `icon.ico` | The app icons for macOS and Windows, used by `jpackage` |
| `screenshot.png` | The screenshot shown in this README |

The images are PNG on purpose: pixel art stays sharp in PNG, while JPG would blur the edges of every pixel.

## Contributing

Suggestions and issues are welcome. Before opening a pull request, run `./gradlew test`.

## Author

**Gonçalo Gatinho**, computer science and engineering student (LEIC).

## License

Distributed under the MIT license. See the [LICENSE](LICENSE) file.
