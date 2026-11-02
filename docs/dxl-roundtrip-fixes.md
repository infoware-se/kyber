# DXL round-trip fixes

Kyber create/update/import uses Domino DXL. Export → import is not always lossless. When a gap is confirmed, we:

1. Add a focused fixer class (for example `FramesetDxlFix`)
2. Register it in `DxlImportFixes` (`REGISTERED_FIXES` + `afterImport`)
3. Document it in the table below
4. Cover the fragile attribute in unit tests and `tools/smoke-dxl-roundtrip.ps1`

Do **not** depend on optional server templates (for example `defaultdesigns.ntf`) for product behaviour. Prefer post-import repair from the DXL the caller already sent.

## Catalog

Registered `DxlImportFixes` / `prepareIncoming` first; related in-import cleanups in the same table so nothing hides in a side section.

| Fix id / area | Type | Symptom | Fix |
| --- | --- | --- | --- |
| `frameset_empty_cells` | `frameset` | Domino export emits nested `<frameset …/>` with no children; importer error 6925 | Pre-import: expand to N `<frame/>` from `columns`/`rows` (`FramesetDxlFix.prepareIncomingDxl`) |
| `frameset_columns_rows` | `frameset` | `columns` / `rows` (e.g. `239px 1`) wrong after import; `$FrameSet` rebuilt badly; nested framesets need the same repair in document order | Re-apply sizes from DXL via JNX `FramesetLayout` tree walk (`FramesetDxlFix`) |
| Orphan XPage classes | `xpage` / `customcontrol` | HTTP 404 when `$ClassData0` missing but index items remain | `DesignElementImport.dropOrphanXspClasses` before/after sign |
| LotusScript compile | `agent` / `scriptlibrary` | Empty module until compile; per-note compile often fails before `Use` deps exist | Soft-fail in `DesignElementImport.compileLotusScript`; ordered retry via `DesignLotusScriptCompile.compileAll` after bulk import |
| Shared Actions identity | `sharedactions` | Domino DXL omits `name` / `$TITLE` / `$$ScriptName` while live note is `$ACTIONS` | `DesignDxl.ensureSharedActionsDxl` before import |
| Tablerow orphans | `subform` / `form` / `page` | Export puts `<par>` / `<compositedata>` under `<tablerow>`; importer error 6922 | `TableDxlFix.prepareIncomingDxl` wraps orphans in `<tablecell>` (skips rows that contain nested `<table>`) |
| Empty tablerow | `subform` / `form` / `page` | Rowspan placeholders as `<tablerow/>` → "Expected end of tag tablerow" (mail `SignatureOption*`) | `TableDxlFix.prepareIncomingDxl` expands to `<tablerow></tablerow>` |
| Template ACL gaps | `acl` | Template ACL DXL omits `-Default-` / `LocalDomainServers` → bulk import aborted | `DesignDxl.ensureProtectedAclEntries` in `DxlDesignPatch.importAcl` |
| Replication formulas | `designnote` | `$ReplFormula` notes are not app design; RT find/sign fails | Skip export in `BulkOnlyNotes`; soft-warn if residual designnote missing after import |
| View lookup | `view` | Folder-style names (`_Admin\…`) not found via `getView(name)` after import | Alias + title scan fallback in `DesignElementImport` |
| View `$Readers` | `view` / `folder` | Source role readers → note missing/`0x1` on fresh NSF | `ViewDxlFix.prepareIncomingDxl` strips `$Readers` |
| DNT Begin w/o End | `form` / `subform` / `page` | `REM {DNT Begin}; …` without End → importer 1301 | `FormulaDxlFix.prepareIncomingDxl` strips unmatched Begin |

### Live coverage set (2026-11-02)

`tools/run-bulk-roundtrip-advanced.ps1` defaults to **every `.ntf` on the Domino server**:

1. `tools/ensure-domino-ntf-copies.ps1` — `CreateFromTemplate` → `kyber_ref\rt_ntf_<name>.nsf` + `KyberEnabled` (map in `ignore/domino-ntf-copies.json`)
2. Bulk RT each copy (`CompareMode=import`), plus live `ias.nsf` / `ias_servers.nsf`, plus OpenNTF-ext `kyber_ref\rt_ext_*.nsf` from `ignore/openntf-ext-copies.json` when present
3. One-shot move of legacy root copies: `tools/migrate-rt-ntf-to-kyber-ref.ps1`

Faster subset: `-CuratedOnly` (10 apps below). Long runs: `tools/watch-roundtrip-progress.ps1` (**120 s** ticks).

| Curated source | NSF | Notes |
| --- | --- | --- |
| `notebook145.ntf` | `rt_notebook.nsf` | Notebook |
| `resrc145.ntf` | `rt_resource.nsf` | Resource Reservations |
| `discussion145.ntf` | `rt_discussion.nsf` | Discussion (XPages / customcontrol / theme) |
| `nntpdi50.ntf` | `rt_nntp.nsf` | NNTP (`designnote`) |
| `doclbw7.ntf` | `rt_doclibrary.nsf` | Doc Library |
| `dominoblog.ntf` | `rt_blog.nsf` | Blog (`navigator`) |
| live | `ias.nsf` | Infoware IAS (`webserviceconsumer`) |
| live | `ias_servers.nsf` | IAS Servers (`webservice*`) |
| `pernames.ntf` | `rt_pab.nsf` | PAB (`sharedcolumn`, `wiringproperties`) |
| `bookmark.ntf` | `rt_bookmark.nsf` | Bookmarks (`wiringproperties`) |

**OpenNTF / external rare-type samples** (not git; stay under `ignore/external-ntf/` + live `kyber_ref\rt_ext_*.nsf`):

| Live NSF | Fills (bulk export) |
| --- | --- |
| `rt_ext_sbtplayground.nsf` | `webcontent`, `javasource` |
| `rt_ext_webservice.nsf` | `jar`, `webserviceconsumer`, `javasource` |
| `rt_ext_xpages.nsf` | `compositecomponent` (+ composite app surface) |
| `rt_ext_XPagesExt.nsf` / `rt_ext_ToDo.nsf` / `rt_ext_doclib8xl.nsf` | broader XPages / doclib |

Sources and licenses: `ignore/external-ntf/SOURCES.txt`. Map/probe: `ignore/openntf-ext-copies.json`, `ignore/openntf-ext-type-probe.json`.

**Coverage notes**

- Every design type seen in Domino NTF + ias + OpenNTF-ext exports is one Kyber already models (typed CRUD and/or bulk). No unknown types found in that set.
- `applet` and `dataconnection` are **exempt from wild fill**: typed CRUD reference is `kybertest.nsf` / `tools/smoke-kyber-api.ps1` (`SmokeApplet`, `SmokeConnection`). They list via `/applets` and `/dataconnections` but are not presently rows in bulk `/export` — treat that as a known export gap, not a missing API type.
- `dbicon` is a singleton (`GET`/`PUT /dbicon`); it is not a separate bulk type row (often appears as `image`).
- `designnote` is bulk-only (no typed list/get/put/delete).
- Mail `SignatureOption*` empty `<tablerow/>` is repaired by `TableDxlFix` (verified on `mail145` / `mailjrn` in live RT). Odd notes in shipped NTFs may still `importFailed` — keep them as regress signal; record outcomes under `ignore/roundtrip-bulk-*.json` / `ignore/roundtrip-all-domino-ntf-*.log`.
- Full AllDominoNtf + OpenNTF-ext pass3 (**108** NSF): `failedOrImportIssues=5/108` (`ignore/roundtrip-bulk-summary.json`, log `ignore/roundtrip-all-domino-ntf-3.log`). Follow-ups:
  - `rt_ext_sbtplayground` form `GlobalOptions` tablerow+`compositedata` **6922** → `TableDxlFix` now on **form/page** (re-smoke after deploy).
  - `gadgetSnippets.js` / `profileNoPhoto-118px.png`: Domino DXLExporter returns **named shells with no payload**. Bulk export now omits those notes and lists them in `skipped[]` (`HOLLOW_DXL_SKIP`) — **source NSF unchanged**; only `scriptlibrary` / `imageresource` get this hollow check.
  - `rt_ntf_teamrm145` PUT `/import` **408** → smoke retries 408/502/503; bulk runner default `-MaxElementsPerChunk 75` (verified green after deploy).
  - `rt_ntf_cppfbws` export **0x1b38** (hidden design) and `rt_ntf_events4` **0x1b7a** (inconsistent data) — Domino/template; exclude or recreate NSF, not a Kyber DXL fix.

Earlier stratified/per-element probe: `ignore/roundtrip-advanced-results.json`. Frameset size retest: `ignore/roundtrip-frameset-retest.json`.

## Testing

| Layer | What |
| --- | --- |
| Unit | `FramesetDxlFixTest`, `TableDxlFixTest`, `ViewDxlFixTest`, `FormulaDxlFixTest`, `HollowDxlExportTest`, `DxlImportFixesTest` (registry ↔ all bulk types) |
| Live (default) | `tools/smoke-dxl-roundtrip.ps1` — **bulk** `GET /export` → chunked `PUT /import` (fresh target); default `-CompareMode import` (import results + frameset size GET). Multi-app regress: `tools/run-bulk-roundtrip-advanced.ps1`. Long runs: progress ticks via `tools/watch-roundtrip-progress.ps1` (**120 s** default). |
| Broader smoke | `tools/smoke-kyber-api.ps1` still export/imports a full smoked NSF |

Do **not** use per-element HTTP for full-app fidelity — it is slow. Bulk import is the product round-trip path. Large NSFs are imported in ~3.5 MB chunks to avoid HTTP 413.

**Typed CRUD and special names:** Vert.x/KEEP returns **404** when `(…)` (and similar) appear **raw** in a path segment. Proper percent-encoding (`%28`/`%29`) works; `.NET`/`EscapeDataString` does **not** encode parentheses. Prefer **`GET|PUT|DELETE /named/{type}?nsfPath=&name=`** for those Domino titles (e.g. subform `(SignatureOption)`). Path `/…/{name}` remains fine for ordinary names.

**How to read the report:** `assert_fail` (frameset sizes) fails the script. `missing` / `importFailed` are real gaps. `extra` often includes blank-chrome notes (`$MainFrameset`, …) created with a new NSF. `dxl_changed` is common — Domino rewrites DXL on import/sign; use it for triage, not as a hard fail.

## Adding a fix

1. Reproduce: export DXL, re-import, show which attribute/item is wrong.
2. Implement repair from the **incoming DXL** (or safe Domino API), not from another NSF.
3. Hook through `DxlImportFixes.afterImport` (or document an in-sign cleanup in the table above).
4. Extend `REGISTERED_FIXES` and this catalog.
5. Add parse/unit coverage and a check in `smoke-dxl-roundtrip.ps1`.
