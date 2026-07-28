# domino-keep-design

KEEP extension for Domino REST API: manage database **design elements** via `nsfPath` using typed DXL CRUD.

| | |
|--|--|
| **JAR** | `keep-design-*.jar` |
| **API** | `/api/design-v1` |
| **Swagger** | tag **`keep-design`** |
| **Defaults NSF** | `keepdesign.nsf` (property `keep.design.defaultNsf`) |
| **Version** | 0.17.0 |

Full endpoint tables and Designer coverage: **[extension/README.md](extension/README.md)**.

## What’s in / what’s out

**In (CRUD):** Forms, Views, Folders, Framesets, Pages, Subforms, Shared Fields/Columns, Outlines, Agents, Shared Actions, Script Libraries (incl. Java libs), **Web Service Providers/Consumers**, Database Script, Images, Files, Style Sheets, Themes, About/Using, Application Properties (GET/PUT), XPages, **Custom Controls**, **Wiring Properties**, **Composite Applications**, **Composite Components**.

**Starters:** `GET /defaults` and `GET /defaults/{type}` — prefer element named `Default` in `keepdesign.nsf`, else embedded DXL in the JAR (`resources/defaults/`). Create/replace stays raw DXL (no merge). Seed NSF with [`tools/create-keepdesign-nsf.ps1`](tools/create-keepdesign-nsf.ps1).

**Bulk only:** Navigators, Data Connections, Jars, Xsp Properties, XPages project notes (`WEB-INF/`, i18n `*.properties`, `.settings/`, …), plus catch-all **`designnote`** for any remaining design note not covered by a typed exporter.

**Bulk + git:** `GET`/`PUT /api/design-v1/designdxl` exports/imports all CRUD + bulk-only + residual design notes. PUT **creates the NSF if missing**. Bridge scripts: [`tools/export-to-repo.ps1`](tools/export-to-repo.ps1), [`tools/import-from-repo.ps1`](tools/import-from-repo.ps1).

**Out of first-class typing:** Applets (usually embedded in form/page DXL). Designer **Icon** = Application Properties (GET/PUT only; no DELETE).

## Build & install

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

Restart REST API after install (`tell restapi quit` / `load restapi`). Keep only one `keep-design-*.jar` in `libs/` (build removes old `iws-design-*.jar`).

## Auth

`POST /api/v1/auth` → Bearer on `/api/design-v1/*`.
