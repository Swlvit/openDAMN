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

## Status (2026-10-04)
- Sandbox removed (user request). App works directly on real folders; user has a fresh backup.
- First interactive run works: menus and plugins visible.
- Fixed: first-run dialog hid behind window (Wayland) -> now auto-uses detected install.
- Hiding verified offline: rebuild of hidden folder from real data, 0 invalid copies.

## Sub-menus (2026-10-08)
User runs BOTH DLLs (memo.submenus.dll 2.1.0 + SC4ResourceLoadingHooks.dll 2.0.1). Scope note above is
superseded: the Submenus-DLL data is now read (read-only), not written.
- `sc4/SubmenuIndex` scans the plugin index for sub-menu buttons (6534284A/2A3858E4, exemplar type 0x28)
  and patch cohorts (05342861/B03697D1 with 0062E78A + AA1DD399). Index-driven: candidates come from
  IndexedFile res TGIs, so ~350 files of 60k are parsed. Scan ~0.2s warm.
- Key finding: hidden copies KEEP `Building Submenus` (only the Lot Resource Key is stripped), so a hidden
  lot leaves the game's own menus but stays in its sub-menu. Tags are only observable in the hidden folder;
  a lot still loading from a plugin can be tagged but we cannot see it.
- `ui/SubmenuDialog` (DAMN > "Lots with sub-menus…"): 5 tabs — hidden w/ sub-menu, hidden untagged,
  missing lots, no button, sub-menus not shown. Copy/save via Ui.showLines.
- `MainWindow.unhideLots(Collection<Tgi>)` unhides selected lots (confirm first; in-memory flags only).
- Measured on the real install: 356 buttons, 70 sub-menus with members, 2928 membership pairs
  (1550 dangling / 911 hidden / 467 visible), 105 sub-menus the game cannot show.
  Of the 81 "empty" sub-menus in memo.submenus.log, 31 also appear in our list; 50 do not because the DLL
  reads the overridden hidden copies and counts them as content — "all members hidden" is a signal only we have.
- Tests: SubmenuIndexTest (synthetic fixtures, 3 cases). SubmenuCheck = manual run against real plugins.

## Next
1. User tests the Game menu: browse lots, add to menu, hide, save; then check in game.
2. Sub-menu next steps (agreed, not built): creating sub-menu buttons, and writing membership
   (patch mode preferred — non-destructive). Dangling references are reported only, never repaired.
3. Curated 57-option list for 0xAA1DD399 is absent from our new_properties.xml (PIM-X has it);
   licensing question open, so a fresh install shows an empty sub-menu tree until a scan finds buttons.
4. Fix issues from feedback.
