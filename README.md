# drapi-iws-design

Infoware KEEP extension for Domino REST API: manage database **design elements** via `nsfPath` using typed DXL CRUD.

| | |
|--|--|
| **JAR** | `iws-design-*.jar` |
| **API** | `/api/design-v1` |
| **Swagger** | tag **`iws-design`** |
| **Version** | 0.11.0 |

Full endpoint tables and Designer coverage: **[extension/README.md](extension/README.md)**.

## What’s in / what’s out

**In (CRUD):** Forms, Views, Folders, Framesets, Pages, Subforms, Shared Fields/Columns, Outlines, Agents, Shared Actions, Script Libraries (incl. Java libs), Database Script, Images, Files, Style Sheets, Themes, About/Using, Application Properties (GET/PUT), XPages, **Wiring Properties**, **Composite Applications**, **Composite Components**.

**Out:** Navigators, Custom Controls, Web Services, Jars (as own type), Applets, Data Connections, Xsp Properties. Designer **Icon** = Application Properties (GET/PUT only; no DELETE).

## Build & install

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

Restart REST API after install (`tell restapi quit` / `load restapi`). Keep only one `iws-design-*.jar` in `libs/`.

## Auth

`POST /api/v1/auth` → Bearer on `/api/design-v1/*`.
