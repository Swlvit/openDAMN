# DAMN Manager 1.3.1 (Delphi): reverse-engineering findings

How this was obtained: the EXE contains a full Borland TD32 debug section (`.debug`, "FB09"). From it I recovered
all 12,784 procedure names and addresses (unit + method), for example `DAMNInstallerU.TDAMNInstaller@CreateDesignUI_2A2AED99`.
I then disassembled the app units with objdump and labelled each UnicodeString literal. I also dumped the DFM forms (TPF0) to text.
Work artifacts are in `/tmp/damnx/work/`:
- `procs.txt`: all procedures
- `allapp.s`: annotated disassembly of the app units
- `strs_by_func.txt`: string literals per function
- `dfm/*.dfm`: the forms as text

Config object field offsets (`TConfig`, global at 0x6988b4) are given below where useful.

---------------------------------------------------------------------------------------------------
## 1. DAMN plugin installer (damnControl.dat / damnDesign.dat)  — MOST IMPORTANT

### Installer form (T_MainInstallerForm)
- Window type radio: "Urgent advice message" (RadioButton1, default checked) / "News ticker" (RadioButton2).
  Stored as `[installation] WindowType` (bool, **1 = Urgent advice**, 0 = News ticker).
- Columns "Quantity": ComboBox `1..6` (DFM default index 2 = "3"). "Width": `300/350/400` (DFM default "400").
- "Menu window size" Height: ComboBox `450 / 700 / 1000` (DFM default 450). Width is a read-only Edit.
- "Maximum number of items": SpinEdit 1..500, default 50.
- Checkboxes: "Reduce the amount of News messages" (default checked), "Install default DAMN Menu" (default unchecked).
- Width shown in the form (`UpdateWindowWidth`): `cols * colWidth + (UrgentAdvice ? 114 (0x72) : 0)`.
  Note: this differs from the value actually written for the Urgent dialog, which uses +128 (see below). That looks like a display bug.

### Generation algorithm (`TDAMNInstaller.UpdateDAMNDesign` / `UpdateDAMNControl`)
- The template file is `<app>\resources\templates\Template_damnDesign.txt` (or `Template_damnControl.txt`). It is loaded with TStringList.LoadFromFile.
- Lines where `Pos('#',line)=1` are skipped (comments).
- `[TEMPLATE]` is followed by 4 lines: the kind (`LTEXT` / `UI` / `LUA`), then Type, Group and Instance as hex. Each hex value is parsed as `StrToInt('$'+line)`.
- Lines up to `[/TEMPLATE]` are collected and joined with CRLF (TStringList.Text, so every line, including the last, ends with CRLF).
- The target DBPF is the existing `<SC4>\Plugins\DAMN\Support\damndesign.dat` (or `damncontrol.dat`). That file comes from Support.zip, which the installer extracts first.
  - The installer looks up the entry by T/G/I (`EntryFastSearch`) and **replaces it in place** (`UpdateInput`). It does not create new entries.
  - If an entry is missing, it logs `UpdateDAMNDesign: LText entry not found` / `UpdateDAMNDesign: LUA entry not found` / `UpdateDAMNControl: LUA entry not found` and aborts.
- Encoding of the replacement data:
  - LTEXT entries: `TLText.EncodeLText` (UTF‑16LE with the standard header).
  - UI and LUA entries: `TLUA.EncodeLUA` (plain 8‑bit text).
- Variable replacement uses `StringReplace(..., [rfReplaceAll])`, which is case-sensitive.

### Values used (all from settings.ini `[installation]`)
In these formulas: `cols` = ColumnsCount, `cw` = ColumnWidth, `wh` = WindowHeight, `maxi` = `[installation] MaxItems`.

**UI 00000000/96A006B0/4A5A89D5 (Urgent-advice dialog; X1..X8)** (`CreateDesignUI_4A5A89D5`)
```
b  = cols*cw + 128          (Delphi; the Java SSPTool used +114)
X1=b  X2=b-102  X3=b-90  X4=b-9  X5=b-91  X6=b-6  X7=b-90  X8=b-68
```
Loop: replace `${X<i+1>}` for i=0..7. The window height 450/436/412/432 is hard-coded in the template (not from settings).
Check: the shipped Support damnDesign.dat has X1=514, X2=412, X3=424, X4=505, X5=423, X6=508, X7=424, X8=446. These are consistent with b=514.

**UI 00000000/96A006B0/2A2AED99 (News window; W0..W8, H0..H2)** (`CreateDesignUI_2A2AED99`). The constants are the same as the Java version:
```
b = cols*cw + 80            h = wh (WindowHeight: 450/700/1000)
W0=b  W1=b+204  W2=b-32  W3=b-48  W4=b-29  W5=b-7  W6=b-14  W7=b-145  W8=b-45
H0=h  H1=h+7    H2=h-14
```
(Replaces `${W0}`..`${W8}` first, then `${H0}`..`${H2}`.)

**LTEXT 2026960B/6A231EA4/DAE1E773 (title row)**: `${T1}` = lang `s2205` ("Menu Browser -").

**LTEXT …/DAE1E774 (item grid; NR, WIDTH)** (`CreateDesignLText_DAE1E774(tpl, maxi, cols, cw)`):
```
WIDTH = cw - 88 - 6          (= cw-94)
rows  = Ceil(maxi / cols);  rem = maxi mod cols;  nr = 0
out: "<table border=\"0\">"
for r in 0..rows-1:
  "<tr>"
  for c in 0..cols-1:
    if rem<>0 and r=rows-1 and c>=rem:  emit "<td></td>" three times
    else: emit Trim(tplBlock) with ${NR}->nr, ${WIDTH}->WIDTH ; nr++
  "</tr>"
"</table>"
```
- Each emitted piece is one TStringList line, so CRLF follows each piece, and the result ends with "</table>\r\n".
- The block is the 3 template lines joined. Trim only strips the outer whitespace.
- This differs from Java, which did not Trim or add a trailing CRLF. Java also used `var8 % cols==0` for rows, but the output is the same.
- `nr` counts only the real cells, 0-based.

**LTEXT …/DAE1E775**: `${T2}` = `s2203` ("Open DAMN menu"). **…/DAE1E776**: `${T3}` = `s2204` ("Additional submenus available >").

**LUA CA63E2A3/4A5E8EF6/0DAE102C in damnControl.dat (C0..C5)** (`CreateControlLUA_0DAE102C`). Replacement order is C0 to C5, so the `${C5}` inside C3 also gets replaced:
```
C0 = s2206  "Home"                       (DAMenu.linkname)
C1 = "100" if WindowType(urgent) else "0" (priority of the 0DAE102C advice)
C2 = s2207  "Search"
C3 = s2208  "Enter a search term (not case sensitive), then press [OK] to view the results. A maximum of ${C5} results can be displayed."
C4 = s2209  "items found"
C5 = IntToStr([installation] MaxItems)
```
The lang strings come from the current UI language file (`resources\lang\<lang>\<lang>.lang`).

### InstallDAMN (from log strings)
1. Create `<SC4>\Plugins\DAMN\`, `Root\` and `Support\`.
2. Unpack `resources\Support.zip` into Support. It contains `damnControl.dat`, `damnDesign.dat` and `DAMN-Indexer.cmd`; the code uses a `*.dat` mask.
3. If ReduceNews is set, unpack `News_Filter.zip`, which gives `news_filter.dat`. If it is unchecked, `news_filter.dat` is deleted.
4. If "Install default DAMN Menu" is set, unpack `RootMenu.zip` into DAMN. It contains `Root/<folders>/zip_placeholder`. If it is missing, the error is "DAMN Root (Default menu) zip file not found".
5. Run UpdateDAMNDesign, then UpdateDAMNControl, then RecreateDAMNIndex.

`CheckDAMNInstallation` returns true only if `DAMN`, `DAMN\Root` and `DAMN\Support` all exist.

Messages:
- "Check if DAMN is already installed..."
- "Install DAMN..."
- "Update the DAMN design ..."
- "DAMN was successfully updated"
- "DAMN could not be installed. Check your permissions and try again"

### SC4 path detection
- Registry key `Software\Maxis\SimCity 4` (or `Software\Wow6432Node\Maxis\SimCity 4` on 64-bit), value `Install DIR`.
- The path is passed through IncludeTrailingPathDelimiter and validated by checking that `<dir>\Apps\SimCity 4.exe` exists.
- If that fails, a SelectDirectory dialog is shown, and DAMN path = `<sel>\Plugins\DAMN\`.
- The user Plugins folder is `CSIDL_PERSONAL\SimCity 4\Plugins\` (created if missing).

---------------------------------------------------------------------------------------------------
## 2. Menu .dat files and DAMN-index.txt

- **Menu file name**: `<menu folder>\DAMN-Menu_<ms>.dat`. `<ms>` is Unix time in **milliseconds**, computed from GetSystemTime (UTC): `(FILETIME - 116444736000000000) div 10000`.
  - It is created when a new menu folder is made (an empty DBPF via `CreateEmptyFile`).
  - It is also created on save if the folder has no existing file. An existing file name is reused, never renamed.
- **Reading**: `FindFirst(folder+'DAMN-Menu_*.dat')`. Every matching file in the folder is read and decoded (all are aggregated).
- **Encoder (TDAMNEncoder.Run), entries written into the menu DBPF**:
  - Random values come from Delphi `Random($7FFFFFFF)`:
    - `G_icon`: one random group for icons.
    - `G_prev`: one random group for previews.
    - `I_lua`: random instance for the LUA file.
    - `I_item`: a new random value per item.
  - Icon PNG: TGI `856DDBAC / G_icon / I_item`, **uncompressed**. Only written if the item has an icon.
  - Preview PNG: TGI `856DDBAC / G_prev / I_item`, **QFS compressed**. Only written if the setting `IncludeAllImages` is on and the item has a preview.
  - Item LText: TGI `2026960B / 6A231EA4 / I_item`, uncompressed. It is built from `Template_LText_Submenu.txt`:
    - `${IMAGE_GID}`/`${IMAGE_IID}` come from the preview TGI if it exists, otherwise from the icon TGI. They are formatted with `%0.8x` (lowercase).
    - `${DESCRIPTION}` and `${INFO}` are filled after Trim, then CRLF→`<br>`, then lone CR removed.
  - LUA: TGI `CA63E2A3 / 4A5E8EF6 / I_lua`, uncompressed. It consists of:
    - `Template_LUA_Header.txt`, with `${LUA_IID}` = lowercase 8-hex of I_lua and `${FILENAME}` = ExtractFileName of the .dat.
    - Per item, `Template_LUA_Item.txt`:
      - `${NR}` = index+1.
      - `${IID}` = lowercase hex of I_item.
      - `${ICON_GID}`/`${ICON_IID}` = G_icon / I_item.
      - `${HIDDEN}` = `true`/`false`.
      - `${TYPE}` = `building`/`flora`/`network`.
      - `${EXEMPLAR_GID}`/`${EXEMPLAR_IID}` are lowercase 8-hex.
    - A final line `end -- if (DAMenu ~= nil)`.
  - All hex output is lowercase. Java used DBPFUtil.toHex, also lowercase.
  - If the menu has no items, the file is still saved (empty), and in folder hide mode the matching hidden file is deleted.
- **DAMN-index.txt (`RecreateDAMNIndex`)**:
  - When it runs: after every save that wrote anything, after rename/delete folder, after Extras→Reindex, at installer finish, and in FormDestroy.
  - Contents: a recursive scan of `<DAMN>\Root\`, keeping **only files whose upper-cased extension = `.DAT`**. The `zip_placeholder` files are excluded; the old .cmd indexer listed `*.*`.
  - Each line is the **absolute path** with backslashes, built as `<DAMNpath>Root\sub\...\DAMN-Menu_x.dat`.
  - Lines are kept in a TStringList with `Sorted=True`, i.e. case-insensitive locale sort.
  - The old file is deleted, the code sleeps 500 ms, then writes with `SaveToFile`. Delphi 2009 default encoding = ANSI (system code page), no BOM, CRLF after every line.
  - The control LUA reads it with `io.open([[Plugins/DAMN/Support/DAMN-index.txt]])`. It needs the substring `Root\`, which is why paths must contain `\Root\`.

---------------------------------------------------------------------------------------------------
## 3. Settings

### settings.ini (in the app folder; TIniFile)
Format: `[section] key = default`.

**`[main]`**
- language=english
- RecognizeItemOrder=1
- HideLotWithoutIcon=1
- ClearClipboardAfterInsert=0
- LotPreviewScaleReset=1
- LoadFilterDataAtStart=1
- ExemplarNotAssignedInfo=1
- ShowLotsError=1
- ShowLotsPropNotFoundError=1
- ShowScanSubfoldersInfo=1
- SaveHelpWindowSizePos=1
- TemplateEmptyNotFilled=1
- TemplateCurrentNr=0

**`[mainwindow]`**
- MainWindowHeight / Width / Left / Top = -1
- MainWindowState = 0
- TopLeftPanelWidth, TopRightPanelWidth, BottomLeftPanelWidth, BottomRightPanelWidth, TopPanelsHeight = -1

**`[helpwindow]`**
- HelpWindowHeight / Width / Left / Top = -1
- HelpWindowState = 0

**`[damn_menu]`**
- DoFullAnalyze=1
- IncludeAllImages=0 (this is "include Lot preview thumbnail")
- RemoveNotAllowedChars=1
- AddLotsToEnd=1
- MaxItems=50

**`[hide_config]`**
- HideMethod=1 (1 = hidden-folder method; 2 = modify original exemplar. No UI for this.)
- UseDatFilename=1
- HiddenWhenDAMNed=1
- HideFolder=zzzzzz_DAMN_Hidden (fallback default when the key is absent). PrepareDefault / first run writes `z_zDAMN_Hidden`.
- HideGID=EA260589 (read as `'$'+value`; unused by the folder method)
- HideExemplarSaveMethod=0 (0 = Binary, 1 = Text, 2 = Original; RadioGroup "Save exemplar as: Binary/Text/Original")

**`[installation]`**
- WindowType=1 (1 = Urgent advice)
- ColumnsCount=2
- ColumnWidth=400
- WindowHeight=450
- MaxItems=50
- ReduceNews=1
- DAMNDefaultRoot=0

**`[visual]`** (TColor ints, BGR)
- MarkHiddenLots=1
- MarkHiddenLotsBgColor=0x808080
- VisualDAMNFolderNoLotsColor=0xFF000011 (clGrayText system colour)
- VisualDAMNFolderNoLotsSubColor=0 (black)
- VisualDAMNFolderToManyLotsColor=0xFF (red)
- VisualDAMNFolderHasLotsColor=0x8000 (green)
- VisualLotsListPloppableColor=0xC1C1FF
- VisualLotsListGrowableColor=0xEAEAEA
- VisualLotsListLandmarkColor=0xFFD5D5
- VisualLotsListRewardColor=0x82AAFF

Other hard-coded config values:
- Allowed-chars sets: `^!$%&/() =?<>+-_#*+.,;:` and `0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`.
- Replacement char `_` (0x5F).
- Currency char `§` (0xA7).

### lottemplates.ini (app folder)
- Only user templates are stored. The built-in "Standard template" (index 0, lang `s8043`) is hard-coded.
- Sections are `[TEMPLATE01]`, `[TEMPLATE02]`, … (`'TEMPLATE%0.2d'`, numbered from list index 1). On load, all sections are read in file order.
- Keys:
```
name=
linknameformat=      linknamepriority=      linknameusepriority=   (bool 0/1)
infoline1format=     infoline1priority=     infoline1usepriority=
infoline2format=     infoline2priority=     infoline2usepriority=
infoformat=          infopriority=          infousepriority=
descriptionformat=   descriptionpriority=   descriptionusepriority=
```
- Formats are trimmed, and CRLF is stored as the literal two characters `\n` (converted back on load).
- A priority is a comma-separated list of the numbers 1..10 (the "replacements list" order). The numbers map to:
  1 `${EX_EX_NAME_KEY}`, 2 `${EX_US_VIS_NAME}`, 3 `${EX_ITEM_DESCR_KEY}`, 4 `${EX_ITEM_DESCR}`, 5 `${EX_ITEM_NAME}`,
  6 `${EXLC_EX_NAME_KEY}`, 7 `${EXLC_US_VIS_NAME}`, 8 `${EXLC_ITEM_DESCR_KEY}`, 9 `${EXLC_ITEM_DESCR}`, 10 `${EXLC_ITEM_NAME}`.
- Internally these come from a name/value list with keys `EXEMPLAR_EXEMPLAR_NAME`, `EXEMPLAR_USER_VISIBLE_NAME_KEY`, `EXEMPLAR_ITEM_DESCRIPTION_KEY`, `EXEMPLAR_ITEM_DESCRIPTION`, `EXEMPLAR_ITEM_NAME`, and the same with the `EXEMPLARLC_` prefix.
- Fill logic (`DAMNTemplatePriortitySetReplace` + `ExchangeFormat`):
  - Without "use priority", each name pattern is replaced directly by its own value.
  - With it, if a pattern's own value is empty, the priority list is walked and the first non-empty value is used.
  - **Bug**: priority entry 9 actually reads `EXEMPLARLC_ITEM_DESCRIPTION_KEY` instead of `..._DESCRIPTION`.
- Other patterns (`DAMNTemplateStrReplace`): `${LOT_SIZE}` (lang s4001..s4004 produce "Size: w x d" / flora / unknown), `${PLOP_COST}`, `${MONTHLY_COST}`, `${BULLDOZE_COST}`, `${OCCUPANCY_GROUP}`, `${BUDGET_DEPARTMENT}`, `${BUDGET_PURPOSE}` (lists joined with ", "), `${LOT_TGI}`.
- If `TemplateEmptyNotFilled` is set, any of the 18 patterns still left is replaced with "".
- **Standard template** (built in):
  - linkname = `${EX_US_VIS_NAME}`, priority `5,2,10,7,1,3,4,6,8,9`, use=1
  - infoline1 = "" (priority same, use=1)
  - infoline2 = `${LOT_SIZE}` (same priority, use=1)
  - info = `Plop cost: ${PLOP_COST} §` CRLF `Monthly cost: ${MONTHLY_COST} §` CRLF `Bulldoze cost: ${BULLDOZE_COST} §`. The labels are lang s8050..s8052 plus ":". Priority `4,3,9,8,1,2,5,6,7,10`, use=1.
  - description = `${EX_ITEM_DESCR_KEY}`, priority `4,3,9,8,1,2,5,6,7,10`, use=1.

### plugins_list.sav (app folder)
- One line per exemplar entry: `TTTTTTTT;GGGGGGGG;IIIIIIII;<file path with the Plugins-folder prefix removed>`.
- Hex is uppercase IntToHex(…,8). The prefix removal uses StringReplace of config `PluginsPath` (with trailing `\`), first occurrence only.
- Built by scanning the plugins folder recursively for `*.DAT` and `*.SC4LOT` (case-insensitive), reading only entries of type `6534284A`.
- Exemplars with ExemplarType (prop 0x10) = 0x10 (LotConfigurations) or 0x1E (Prop) are skipped. So the list effectively holds building and flora exemplars.
- If nothing is found, a single line " " is written.
- Saved with TStringList.SaveToFile (ANSI, CRLF).
- `FindByTGI` splits each line on `;`.

---------------------------------------------------------------------------------------------------
## 4. Hidden Lots (HideMethod=1, the folder method; `TDAMNEncoder.HideElementsMethodFolder`)

### File name (`HideElementsMethodFolder_PrepareFileName`)
1. Start from `ExtractFileDir(menu .dat path)`. If the item list is non-empty, use the first item's stored menu path instead.
2. Remove the DAMN path (`<SC4>\Plugins\DAMN\`); this is the first occurrence, case-sensitive.
3. Strip one leading `\` and one trailing `\`.
4. Replace every `\` with `_`.
5. The file is `<UserPluginsPath><HideFolder>\DAMN_<that>.dat`.

Example: `…\Plugins\DAMN\Root\Civic\Energy management\Power plants` → `DAMN_Root_Civic_Energy management_Power plants.dat`. Case is preserved; it starts with `Root` and is not lower-cased as the help claims.

### Per save of a menu
- The existing hidden file is opened/created.
- For each item:
  - Items with the flag at +0x88 set are skipped. That flag marks Maxis/original lots, which are never hidden.
  - If the item is hidden and (not already present in the hidden file, or "Update hidden lots" is forced):
    - The exemplar is taken from memory. If it has 0 props (i.e. the menu was re-loaded), it is located via plugins_list.sav (`FindByTGI` → open the file → decode).
    - If not found, the user is asked to rescan (s4102, `UpdatePluginsList`) and the lookup is retried. Otherwise the log says `Exemplar not found for TGI: %s` / "Plugins folder was search for exemplar with TGI %0.8x %0.8x %0.8x - information was not found."
    - A copy is made. If type = `building`, property **0xEA260589 (Lot Resource Key)** is removed; if `flora`, **0x8A2602BB (Item Button ID)** is removed.
    - It is then encoded according to HideExemplarSaveMethod:
      - 0: `ExemplarEncodeEQZB`, binary. The signature is forced to `EQZB1###`.
      - 1: `ExemplarEncodeEQZT`, text.
      - 2: `ExemplarEncode`, keeps the original format/signature.
      - Anything else raises "Not supported value of HideExemplarSaveMethod setting (%s)".
    - The result is added with the **same TGI as the original exemplar** (overwriting it if present), **QFS-compressed**.
  - If the item is not hidden, its TGI entry is removed from the hidden file (`UnHideLot`).
- Afterwards `RemoveUnnecesary` deletes entries whose TGI is no longer an item of this menu, and the file is saved.
- It is a pure exemplar-override approach: no LUA `reward_state.HIDDEN` is generated by the manager.
  - However, damnControl's LUA itself calls `create_reward_cityplanning(item.iid)` with `reward_state.HIDDEN` for `item.hidden` items. That is the in-game half, and it comes from the DAMN plugin template.
- Hide/Unhide/Update on a folder recurses into submenus. "Update hidden lots" forces re-copying.
- The lot-list "Is hidden" detection (`TLotElementsWorker.IsHidden`) checks for missing 0xEA260589 or 0x8A2602BB, plus LText 0x2026960B.

### HideMethod=2 (legacy, no UI)
- Modifies the original plugin file in place, after backing it up to `<file>.org`.
- It removes the property and stores an LText `2026960B/<HideGID>/<IID>` containing `DESC_LOT_RESOURCE_KEY=0x%8X` or `DESC_ITEM_BUTTON_ID=0x%8X`.
- Unhide parses `=0x` from that LText.

### The 1.3.1 "major crash" fix
- I could not identify it with certainty because there is no 1.3.0 binary to diff.
- The relevant path is the exemplar re-encode (`ExemplarEncodeEQZB`, which forces signature `EQZB1###` even if the source was a cohort or text, and rebuilds property records) plus `AddFile` (compress flag).
- 1.0.3 had already fixed "text→binary conversion crashes".
- Recommendation for the rewrite: copy the **original raw exemplar bytes** and remove only the property (method "Original"), keeping the signature and parent cohort intact.

---------------------------------------------------------------------------------------------------
## 5. Original SimCity (Maxis) lots (`TDAMNSimcityOriginalLots.Load`, `TSimcity1DAT`)

- Nothing is embedded in the EXE. The data is read at runtime from the game install:
  - `SimCity_1.DAT`: exemplars, PNG icons and LTexts. It is pre-read in a background thread (`Simcity_1DAT_Read`), and the main form has a `Simctity1DATLoaded` callback.
  - `SimCityLocale.DAT`: names and descriptions.
- Both files are found with `DBPFUtilsU.FindFileDAT`, which searches the install folder recursively. Since 1.0.7, `SimCityLocale.DAT` can be in a language subfolder.
- If a file is missing, the error is lang s13001: "File "%s" was not found in SimCity installation folder."
- An exemplar is accepted if ExemplarType (0x10) is 2 (Buildings) or 0x0F (Flora) **and** it has 0xEA260589 (LotResourceKey) or 0x8A2602BB (Item Button ID).
- The lot config is found by `FindExemplarLotConfiguration`, which scans LC exemplars whose 0xA8FBD372 / LotObject points to the building and reads the size from 0x88EDC790.
- Name, description and cost come from these properties:
  - 0x20 Exemplar Name
  - 0x8A416A99 User Visible Name Key
  - 0xCA416AB5 Item Description Key (TGI is looked up as an LText, presumably in the locale file)
  - 0x49CAC341 Plop Cost
  - 0xEA54D286 Monthly cost
  - 0xAA1DD396 Occupant Groups
  - 0xEA54D283 / 0xEA54D285 Budget department / purpose
  - If a property is missing, the value is -99.
- Icon: PNG `856DDBAC / 6A386D26 / <Item Icon 0x8A2602B8 value>`, or the Item Button ID value when there is no Item Icon. The icon is cut from the 4-state strip.
- The list is sorted with a TStringList key built from `%0.3d` plus flags; landmark (+0x44) and reward (+0x45) entries are grouped.
- These items get the "original" flag (+0x88). They are never written to the hidden folder.
- When encoded, `EXEMPLAR_GID/IID` come from the alternate exemplar (for flora, the element's own TGI fields).

---------------------------------------------------------------------------------------------------
## 6. Network lots (T_MainNetworkLotsAddForm.ParseNetworkFile / CreateNetworkElement)

### Parsing
- The file is loaded into a TStringList.
- Lines are deleted if they are empty or if their first char is `!`, `#` or `;`.
- Each remaining line is split with `ExtractStrings([';','='], ...)` into `[key, value, comment, ...]`.
  - The line is used **only if there are more than 2 parts**, so a trailing `;comment` is required. The comment becomes the Linkname.
  - Only lines whose Trim(key) is `RotationRing` or `AddTypes` (case-insensitive) are used.
- The value is split on `,` and **only the first ID** is used: `ID = StrToIntDef('$' + prefix + Trim(firstValue), 0)`.
  - `prefix = '6A47'` if the extension is `.rul` (NAM short IDs, e.g. `1020` gives 0x6A471020).
  - Otherwise the prefix is empty (TXT files contain full 8-hex IDs).
- Samples: `RotationRing = 8c329937 ;Road` (TXT) and `RotationRing = 1020, 11020, ... ;elevated highway` (RUL).
- If nothing is found, lang s14010 is shown.

### Icon and element
- Icon file: `ExtractFilePath(sourceFile) + IntToHex(ID,8) + '.png'`. IntToHex is uppercase; the shipped files are lowercase, which works on Windows only because it is case-insensitive.
  - The icon is loaded with MakeIconFromFile (last 44x44 frame of 44x176).
  - If "Only with icons" is checked and the png is missing, the entry is skipped. If unchecked, a "No icon" icon is generated.
- The element gets:
  - exemplar TGI = `6534284A / Random($7FFFFFFF) / ID`, so the LUA `item.iid` = the button ID in lowercase hex.
  - exemplar type field = 6 (Road).
  - origin flag (+0x88) = 2 (network).
  - EXEMPLAR_EXEMPLAR_NAME = the comment/name.
  - costs = -99.
  - menu type `"network"`. damnControl uses `game.tool_plop_network(tonumber(iid,16))`.
- Manual mode (Button ID + name + image): `TestIID` accepts hex with an optional `0x` prefix (s14002..s14004 errors).

## 7. HTML export (DAMNExportMenuInfoU)

### Templates
- `resources\ExportTemplate.zip` contains `htmlbody.html`, `menubody.html` and `menuitembody.html`.
- The files are split into parts on the line `###`.

### Output
- Output goes to `<chosen dir>\DAMN_Menu_Contents.html`.
- Icons are written to `<dir>\MenuIcons\` and previews to `<dir>\MenuPreviews\`.
  - The file name is derived from the menu path, with ' ' replaced by '_', plus the item TGI and `.png`.
- When done, the result is opened via `file:///`.

### htmlbody.html placeholders
- `${TITLE}`, `${HEADER_R1}`, `${HEADER_R2}`
- `${HIDEALLMSG}`=s15010, `${SHOWALLMSG}`=s15011, `${HIDEMSG}`=s15012, `${SHOWMSG}`=s15013
- `${MENUITEMWIDTH}` = column width
- `${CONTENT}`

### menubody.html
- Parts: header/div, table open, `<tr>`, cell `<td class="mic">${MENUITEM}</td>`, `</tr>`, table close, div close.
- One block is emitted per menu (folder).
- `${MENULOCATION}` = path relative to the DAMN folder, with `\` replaced by `->`.
- `${DIVID}`=`DIV<hash>` and `${BUTTONID}`=`BID<hash>` (BobJenkinsHash of the path).
- Items are laid out in `cols` columns with `Ceil(n/cols)` rows; empty cells are filled with an empty `${MENUITEM}`.

### menuitembody.html
- Its `###` parts are optional rows, included or omitted according to the export checkboxes (preview, row1, row2, info, description, TGI).
- `${ROWSPAN}` / `${COLSPAN}` are computed from the number of enabled rows.
- Other placeholders: `${ICONFILE}`, `${PREVIEWFILE}`, `${LINKNAME}`, `${INFOTEXTROW1}`, `${INFOTEXTROW2}`, `${INFO}`, `${DESCRIPTION}`, `${TGI}`.
- Empty fields become `&nbsp;`.
- INFO and DESCRIPTION go through CRLF -> `<br>`, and the currency sign is replaced by `&#0167;`.


---------------------------------------------------------------------------------------------------
## 8. Lot detection / icons / misc (LotElementsWorkerU)
- Plugin files scanned: `*.dat` and `*.sc4lot` (`FindFiles_DATSC4LOT`). Only entries of type `6534284A` are read.
- Icon (`SetIcon`): the PNG `856DDBAC / 6A386D26 / <Item Icon 0x8A2602B8>` is used.
  - For flora, the Item Button ID (0x8A2602BB) is used instead.
  - The icon is taken as the last 44×44 frame of a 44×176 strip.
  - If there is no icon, a "No icon" image is generated (lang s4410/s4411). These lots are hidden from the list when `HideLotWithoutIcon` is set.
- Preview (`SetPreview`): also from PNG group 6A386D26.
- Ploppable / landmark / reward are computed as described in the help. Landmark is OccupantGroup 0x150A; reward is 0x150B.
- The `#m:#` / `#d:#` fill is done by `FillPatternsInTexts` (not analysed in detail).

## 9. Filters
- The filters section is the `<FILTERS>`/`<CATEGORY>` tree in new_properties.xml.
- It is evaluated in `T_PluginsLotsListFrame.IsFiltered` together with `ExemplarPropertyFilter*` units.
- Element types: `PROPERTY ID Value=` (must equal), `PROPERTY ID Eval=` (named check), `NEEDED ID [Value]` (property must exist, optionally with a value), `NOT ID` (property must be absent).

## 10. Known bugs / quirks
- The installer form shows width `cols*cw+114` but writes `cols*cw+128` into the Urgent dialog UI.
- Template priority #9 reads the wrong key (see section 3).
- The hidden-file name keeps the original case (`DAMN_Root_...`), which contradicts the help.
- DAMN-index.txt is ANSI, so non-ANSI folder names break.
- The default HideFolder differs between code paths: `zzzzzz_DAMN_Hidden` when the INI key is absent vs `z_zDAMN_Hidden` from PrepareDefault.
