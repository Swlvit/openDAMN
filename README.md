# OpenDAMN
_Completely coded by Claude_  
            !!!DAMN is officially deprecated!!!
 
OpenDAMN is free software under the **GNU General Public License, version 3 or
later**. See [LICENSE](LICENSE).

It interoperates with, and ships files from, other people's work:

- **DAMN** — the menu system itself, by Daeley.
- **SSPTool** by Stefan Wertich and **DAMN Manager** by Yildun — the original tools,
  and the source of the DAMN engine files in `src/main/resources/org/opendamn/engine/`,
  which are GPL.
- **`new_properties.xml`** — the community property definitions maintained by
  **memo33** for the Submenus DLL project.
- **FlatLaf** by FormDev Software (Apache 2.0) — the look and feel.

SimCity 4 is a trademark of Electronic Arts. This project is not affiliated with or
endorsed by EA or Maxis.

It is a tool for creating files for plugin Daeleys Advanced Menu Navigator.

A modern, open-source manager for **DAMN** menus in SimCity 4 — the in-game plugin
browser that lets you organise custom lots into your own menu structure.

OpenDAMN is a ground-up replacement for DAMN Manager and SSPTool, written in Java
with a native Swing interface. It reads and writes the same on-disk format, so it
works with menus you already have.

## Features

- **Browse and edit DAMN menus** — create, rename, move and delete menus, and drag
  lots into them. The full tree round-trips byte-exactly against real installations.
- **Hide lots from the game's own menus** — hidden lots leave the Maxis menus but
  stay available through your DAMN menus. The hidden folder is regenerated from the
  original plugin files on every save, so copies never go stale.
- **Find lots** — a searchable catalogue of every ploppable lot in your plugins, with
  icons, sizes, costs and category filters.
- **Add Maxis lots and network items** — pull the original SimCity building set and
  roads, rails and highways into your menus.
- **Sub-menu awareness** — reads the sub-menus created by the *Submenus DLL* and
  *SC4ResourceLoadingHooks DLL*, and reports lots that are hidden, unreachable, or
  missing, and sub-menus the game will not display.
- **Import and export** — share menus as ZIP archives or publish them as web pages.
- **Health check and statistics** — find duplicate items, broken links, over-full
  menus and load-order problems before they reach the game.

## Requirements

- **Java 21 or newer** to run the prebuilt jar.
- SimCity 4 (any edition). Wine and Proton installations are detected automatically.
- The **DAMN plugin** installed in your game's `Plugins/DAMN` folder. OpenDAMN can
  install it for you from the DAMN menu.
- Optional: [Submenus DLL](https://github.com/memo33/submenus-dll) and
  *SC4ResourceLoadingHooks* for sub-menu support.

## Running

Download `OpenDAMN.jar` from the latest release and run:

```sh
java -jar OpenDAMN.jar
```

On Linux and macOS you can also use the bundled launcher:

```sh
./OpenDAMN.sh
```

On Windows, double-click `OpenDAMN.bat`.

Settings are stored per user:

| Platform | Location |
|---|---|
| Linux | `~/.config/opendamn/` |
| macOS | `~/Library/Application Support/OpenDAMN/` |
| Windows | `%APPDATA%\OpenDAMN\` |

## Building from source

```sh
./gradlew build        # compile and run the tests
./gradlew run          # build and launch
./gradlew fatJar       # single runnable jar in build/libs/
```

The build needs a JDK 21 toolchain; Gradle downloads the wrapper on first use.

### Testing against real plugin data

Most tests use synthetic fixtures. The read-only checks that exercise real
installations are opt-in:

```sh
OPENDAMN_DAMN_SAMPLE="/path/to/DAMN" \
OPENDAMN_SAMPLES="/path/to/Plugins" \
./gradlew test
```

## How hiding works

SimCity 4 resolves resources by load order: for any given type/group/instance, the
copy in the last-loading file wins. OpenDAMN hides a lot by writing a copy of its
exemplar into a folder that sorts after everything else, with the property that
makes the game menu-visible removed. The original plugin file is never modified.

Consequences worth knowing:

- Unhiding regenerates the folder, so no stale copies are left behind.
- A lot whose plugin you later remove stays hidden until you unhide it.
- The hidden folder is rewritten in full on save; anything else in it is left alone.

## Sub-menus

The Submenus DLL lets a lot appear in a named group inside a toolbar, and a lot
joins one in two ways: it carries a `Building Submenus` list, or a separate *patch
cohort* names it as a target. OpenDAMN reads both and cross-references them with
what you have hidden.

The **DAMN → Lots with sub-menus…** window reports:

| Tab | Meaning |
|---|---|
| Hidden with sub-menu | Hidden in DAMN, but still occupying a sub-menu |
| Hidden, untagged | Placed in a sub-menu by a patch, with no tag of its own |
| Missing (sub-menu) | A sub-menu lists it, but no plugin provides it |
| Missing (DAMN menu) | A DAMN menu lists it, but no plugin provides it |
| No button | Belongs to a sub-menu whose button no plugin provides |
| Sub-menus not shown | Sub-menus the game will not display, and why |

Reading is read-only. Unhiding only changes your DAMN menus; no third-party plugin
is ever modified.

This build reads sub-menus but does not create or edit them. Writing sub-menu
membership is planned.

## Credits and licence

OpenDAMN is free software under the **GNU General Public License, version 3 or
later**. See [LICENSE](LICENSE).

It interoperates with, and ships files from, other people's work:

- **DAMN** — the menu system itself, by Daeley.
- **SSPTool** by Stefan Wertich and **DAMN Manager** by Yildun — the original tools,
  and the source of the DAMN engine files in `src/main/resources/org/opendamn/engine/`,
  which are GPL.
- **`new_properties.xml`** — the community property definitions maintained by
  **memo33** for the Submenus DLL project.
- **FlatLaf** by FormDev Software (Apache 2.0) — the look and feel.

SimCity 4 is a trademark of Electronic Arts. This project is not affiliated with or
endorsed by EA or Maxis.
