# domino-keep-design

KEEP extension for Domino REST API: manage database **design elements** via `nsfPath` using typed DXL CRUD.

| | |
|--|--|
| **JAR** | `keep-design-*.jar` |
| **API** | `/api/design-v1` |
| **Swagger** | tags **`design-element`** (typed CRUD), **`design-handler`** (`/defaults*` + bulk GET/PUT `/designdxl`) |
| **Defaults NSF** | `keepdesign.nsf` (property `keep.design.defaultNsf`) |
| **Version** | 0.19.20 |

Full endpoint tables and Designer coverage: **[extension/README.md](extension/README.md)**.

## What’s in / what’s out

**In (CRUD):** Forms, Views, Folders, Framesets, Pages, Subforms, Shared Fields/Columns, Outlines, Agents (**LotusScript / Formula / Simple action / Java**), Shared Actions, Script Libraries (**LotusScript / client JS / SSJS / Java**), **Web Service Providers/Consumers**, Database Script, Images, Files, Style Sheets, Themes, About/Using, XPages, **Custom Controls**.

**Starters:** `GET /defaults` and `GET /defaults/{type}` — if `keepdesign.nsf` has elements of that type, use the first by alphabetical name; else embedded DXL in the JAR (`resources/defaults/`). Script libraries: `?kind=lotusscript|javascript|ssjs|java`. Agents: `?kind=lotusscript|formula|simpleaction|java`. Create: raw DXL body, or empty body with `?kind=&name=` for script libraries and agents.

**Blank NSF baseline:** snapshot of what Domino puts in a new blank database lives under [`extension/src/main/resources/defaults/blank/`](extension/src/main/resources/defaults/blank/) (`acl`, `applicationproperties`, `$DBIcon`, untitled default view) + `manifest.json` — reference so sync does not treat these as removable app design.

**Bulk only:** Navigators, Data Connections, Jars, Xsp Properties, XPages project notes (`WEB-INF/`, i18n `*.properties`, `.settings/`, …), **Wiring Properties**, **Composite Applications**, **Composite Components**, plus catch-all **`designnote`** for any remaining design note not covered by a typed exporter.

**Bulk + git:** `GET`/`PUT /api/design-v1/designdxl` exports/imports all CRUD + ACL + bulk-only + residual design notes. PUT **creates the NSF if missing** (only bulk import; per-type CRUD requires an existing NSF). API never deletes databases. Bridge scripts: [`tools/export-to-repo.ps1`](tools/export-to-repo.ps1), [`tools/import-from-repo.ps1`](tools/import-from-repo.ps1). ACL also has singleton `GET`/`PUT /acl` (Manager for PUT).

**Out of first-class typing:** Applets (usually embedded in form/page DXL). Designer **Icon** / Application Properties (no dedicated CRUD; may still appear in bulk `/designdxl`).

## Build & install

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

Restart after install is automatic with `-InstallToDrapi`: Notes COM `tell restapi quit` → replace JAR in `libs/` → `load restapi` (see [`tools/restart-restapi-com.ps1`](tools/restart-restapi-com.ps1)). Prefer this over `tell restapi reload` / Windows service restart — JAR locks clear and COM stays available while restapi is down.

## Auth

`POST /api/v1/auth` → Bearer on `/api/design-v1/*`.
