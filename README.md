# Kyber

Infoware Domino REST API extension for managing database **design elements** via `nsfPath` using typed DXL CRUD. Not an HCL product — runs as a KEEP/DRAPI extension JAR.

| | |
|--|--|
| **JAR** | `infoware-kyber.jar` |
| **API** | `/api/kyber-v1` |
| **Catalog** | OpenAPI version id **`kyber`** (Admin UI card title) |
| **Swagger tags** | **`design-element`** (typed CRUD), **`design-handler`** (`/defaults*` + bulk `/designdxl`) |
| **Defaults NSF** | `kyber.nsf` (property `kyber.defaultNsf`) |
| **Version** | 0.24.2 |
| **Repo** | https://github.com/infoware-se/kyber |

Full endpoint tables and Designer coverage: **[extension/README.md](extension/README.md)**.

## What’s in / what’s out

**In (CRUD):** Forms, Views, Folders, Framesets, Pages, Subforms, Shared Fields/Columns, Outlines, Navigators, Agents (**LotusScript / Formula / Simple action / Java / Imported Java**), Shared Actions, Script Libraries (**LotusScript / client JS / SSJS / Java**), Code → Java sources (**class / interface / enum**), Jars, Applets, **Web Service Providers/Consumers**, Data Connections, Database Script, Images, Files, Style Sheets, Themes, About/Using, XPages, **Custom Controls**, Wiring Properties, Composite Applications/Components, Application Properties, **DB icon (`$DBIcon`)**, Xsp Properties, ACL.

**Starters:** `GET /defaults` and `GET /defaults/{type}` — if `kyber.nsf` has elements of that type, prefer first match by name; else embedded DXL in the JAR (`resources/defaults/`). Script libraries: `?kind=lotusscript|javascript|ssjs|java`. Agents: `?kind=lotusscript|formula|simpleaction|java|importedjava`. Java sources: `?kind=class|interface|enum`. Create: raw DXL body, or empty body with `?kind=&name=` for script libraries, agents, and javasources.

**Blank NSF baseline:** snapshot of what Domino puts in a new blank database lives under [`extension/src/main/resources/defaults/blank/`](extension/src/main/resources/defaults/blank/) (`acl`, `applicationproperties`, `$DBIcon`, untitled default view) + `manifest.json` — reference so sync does not treat these as removable app design.

**Bulk only:** XPages project notes (`WEB-INF/` except `xsp.properties`, i18n `*.properties`, `.settings/`, …) as `xpagesresource`, plus catch-all **`designnote`** for any remaining design note not covered by a typed exporter.

**Bulk + git:** `GET`/`PUT /api/kyber-v1/designdxl` exports/imports all CRUD + ACL + bulk-only + residual design notes. PUT **creates the NSF if missing** (only bulk import; per-type CRUD requires an existing NSF). API never deletes databases. Bridge scripts: [`tools/export-to-repo.ps1`](tools/export-to-repo.ps1), [`tools/import-from-repo.ps1`](tools/import-from-repo.ps1). ACL also has singleton `GET`/`PUT /acl` (Manager for PUT).

**Out of first-class typing:** residual untitled notes (`designnote` bulk only).

## Build & install

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

Installs `infoware-kyber.jar` into `%DRAPI_HOME%\libs\` (replaces any previous Kyber/legacy design JARs). Restart with `-InstallToDrapi`: Notes COM `tell restapi quit` → replace JAR → `load restapi` (see [`tools/restart-restapi-com.ps1`](tools/restart-restapi-com.ps1)).

## Auth

See Domino REST API admin docs / your site `.env` (`REST_ADMIN_USER` / `REST_ADMIN_PASSWORD`).
