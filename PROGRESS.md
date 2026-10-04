# OpenDAMN – progress notes

Modern open-source (GPLv3) replacement for DAMN Manager / SSPTool (SimCity 4 DAMN menus).
Java 21 + Swing (FlatLaf), Gradle (`./gradlew build`, run: `./gradlew run`).

## Scope (agreed with user)
- DAMN menus + hiding lots from the game's own menus. NO Submenus-DLL tool (sc4-pimx does that).
- NEVER write to the user's real install without explicit permission. Test on copies in /tmp.
- Commit often; keep this file current.

## User paths (read-only unless permitted)
- Game: /home/tux/Games/smc4/pfx/drive_c/Program Files (x86)/Maxis/SimCity 4 Deluxe Edition/
- User plugins: /home/tux/Games/smc4/pfx/drive_c/users/steamuser/My Documents/SimCity 4/Plugins/
  (hidden folder there: zzzzzz_DAMN_Hidden; also "~Documents" folder which sorts after z!)
- Sample copy of a working DAMN install: /home/tux/damn/example_working_installed_damn/
- Original programs: /home/tux/damn/original/*.zip. Reverse-engineering notes: /tmp/damnx/DELPHI_FINDINGS.md
  (copy kept in docs/DELPHI_FINDINGS.md)

## Done
- dbpf/: DBPF read/write, QFS codec, exemplar (binary+text, byte-exact round trip on 638 real exemplars), LText.
- damn/: menu file codec (round-trips all 10,280 real items / 932 menus), DamnMenus (tree edits, crash-safe save
  with backups, index rebuild), DamnInstaller (window geometry, control script with robust index loader +
  embedded index fallback; verified in real Lua 5.1 with all real menus), templates, ZIP import/export, HTML export.
- sc4/: PluginIndex (60k files in ~5s, ~1.6s cached), LotCatalog, HiddenLots (regenerates hidden folder from
  original exemplars), GamePaths (Wine/Proton detection), LotFilters (new_properties.xml), HealthCheck, NetworkList.
- ui/: full main window + dialogs; compiles. NOT yet run/tested interactively.

## Next
1. Verify hiding end-to-end on a copy (compare with existing zzzzzz_DAMN_Hidden; check load order).
2. Sandbox mode so the user can try the GUI safely on copies.
3. Run the GUI, fix issues, then ask user to test.

## Tests
`OPENDAMN_DAMN_SAMPLE=".../example_working_installed_damn/Installed Directory/DAMN" OPENDAMN_SAMPLES="<plugins>" ./gradlew test`

## Sandbox mode
- SandboxSetup copies DAMN+hidden to /tmp, symlinks game+plugins (read-only).
- User can experiment safely, then apply changes back with File > Apply sandbox.
