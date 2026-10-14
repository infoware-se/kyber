# Kyber — Infoware REST API for design elements (`infoware-kyber.jar`)

Not an HCL product. Runs as a KEEP/DRAPI extension.

| | |
|--|--|
| **Version** | 0.24.2 |
| **API** | `/api/kyber-v1` |
| **Catalog id** | `kyber` (Admin UI / OpenAPI version key) |
| **Spec** | `openapi/openapi.kyber.json` |

Manage database **design elements** via Domino REST API. Target database is selected with query param **`nsfPath`**. Standard collection CRUD:

| | Path | operationId |
|--|------|-------------|
| **List** | `GET /agents` | `listAgents` |
| **Create** | `POST /agents?name=` (empty → default; **409** if exists) | `createAgent` |
| **Get** | `GET /agents/{name}` | `getAgent` |
| **Update** | `PUT /agents/{name}` (**404** if missing) | `updateAgent` |
| **Delete** | `DELETE /agents/{name}` | `deleteAgent` |

Same pattern for other named types (`/forms`, `/scriptlibraries`, …). Write responses include exported `dxl`.

## Supported types

| Type | List | Create | Update | Get | Delete |
|------|------|--------|--------|-----|--------|
| Named (form, view, page, …) | `GET /{types}` | `POST /{types}?name=` | `PUT /{types}/{name}` | `GET /{types}/{name}` | `DELETE /{types}/{name}` |
| About / Using / Database Script | — | `POST /about` etc. | `PUT /about` etc. | `GET` | `DELETE` |
| Xsp Properties | — | `POST /xspproperties` | `PUT /xspproperties` | `GET` | `DELETE` |
| DB icon (`$DBIcon`) | — | — | `PUT /dbicon` | `GET /dbicon` | — |
| Application Properties / ACL | — | — | `PUT` | `GET` | ACL: — / App props: — |

`$DBIcon` is excluded from `GET /images` — use `/dbicon`. Kinded types (`agent`, `scriptlibrary`, `javasource`) take `?kind=` + `?name=` on empty-body `POST`.

OpenAPI operationIds: `listX` / `getX` / `createX` / `updateX` / `deleteX`.

All paths are under `/api/kyber-v1` and require `?nsfPath=`.

## Bulk export/import (`design-handler`)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/designdxl?nsfPath=` | Export all supported elements as `{ nsfPath, elements: [{ type, name?, dxl }], skipped: [{ type, name?, message }] }` |
| PUT | `/designdxl?nsfPath=&dbTitle=` | Import that array; **create NSF if missing**; optional `dbTitle` sets DB title on create |

Swagger tag: **`design-handler`** for `/defaults`, `/defaults/{type}`, and `/designdxl` (per-type CRUD stays under **`design-element`**).

**Defaults resolution** (`GET /defaults/{type}`): if `kyber.nsf` (or `kyber.defaultNsf`) is available and contains that type (and `?kind=` when kinded), use the **first** match by case-insensitive name; otherwise the embedded JAR starter under `resources/defaults/`.

Element `type` values match repo layout (`form`, `about`, `image`, …). Bridge scripts in repo root [`tools/`](../tools/):

```powershell
.\tools\export-to-repo.ps1 -RestUrl https://host:8880 -NsfPath test/app.nsf -OutDir .\kyber
# Java in script libraries, agents, applets: strips <javaresource> Base64 and writes sibling .java (default).
# Keep compiled class in DXL: -ReadableJava:$false
.\tools\import-from-repo.ps1 -RestUrl https://host:8880 -NsfPath test/restored.nsf -InDir .\kyber
```

Repo tree (folders = API type / CRUD layout; this repo ships starters under [`kyber/`](../kyber/)):

```
kyber/
  manifest.json          # type (API), doctype, path, name?
  forms/newform-….dxl
  views/newview-….dxl
  images/….dxl
  about/….dxl
  ...
```

`import-from-repo.ps1` reads `manifest.json` (API `type` + path).

After PUT, the API compiles LotusScript (script libraries first) and returns a `lotusscript` summary. Notes "Error processing use list module" after a restore usually means code was not compiled — re-import or run Designer **Recompile All LotusScript**. Some complex libraries (e.g. DDM) can still report syntax errors after DXL round-trip — open those in Designer if needed.

**Bulk-only types** (no list/get/put/delete endpoints; only in `/designdxl`): `xpagesresource` (incl. `WEB-INF/` except `xsp.properties`, i18n `*.properties`, `.settings/`, `icons/`, `Local/`), and catch-all `designnote` for any other remaining design note (UNID-deduped after typed exporters). Starters: `GET /defaults/{type}` for `xpagesresource` (not `designnote`).

**Blank NSF baseline** (reference DXL, not starters): [`resources/defaults/blank/`](src/main/resources/defaults/blank/) — `acl.dxl`, `applicationproperties.dxl`, `image-DBIcon.dxl`, `view-default.dxl`, plus `manifest.json` from a Domino blank database.

## Coverage (Domino Designer)

Mapped to the Designer outline. "In" = typed DXL CRUD; "Bulk" = `/designdxl` only.

### Included

| Designer section | Element | API |
|------------------|---------|-----|
| Forms | Forms | `/forms` |
| Views | Views | `/views` |
| Folders | Folders | `/folders` |
| Framesets | Framesets | `/framesets` |
| Pages | Pages | `/pages` |
| Shared Elements | Subforms | `/subforms` |
| Shared Elements | Fields | `/sharedfields` |
| Shared Elements | Columns | `/sharedcolumns` |
| Shared Elements | Outlines | `/outlines` |
| Code | Agents | `/agents` — kinds `lotusscript` \| `formula` \| `simpleaction` \| `java` \| `importedjava` |
| Code | Shared Actions | `/sharedactions/{name}` |
| Code | Script Libraries | `/scriptlibraries` — kinds `lotusscript` \| `javascript` \| `ssjs` \| `java` (defaults + empty-body create) |
| Code | Database Script | `/databasescript` (singleton) |
| Application Configuration | Application Properties | `/applicationproperties` (singleton; GET/PUT update-only, no create/DELETE — always present with NSF) |
| Application Configuration | Database icon (`$DBIcon`) | `/dbicon` (singleton; GET/PUT update-only, no create/DELETE — always present with NSF; not under `/images`) |
| Application Configuration | Xsp Properties | `/xspproperties` (singleton; GET/POST/PUT/DELETE — optional `WEB-INF/xsp.properties`) |
| Resources | Images | `/images` |
| Resources | Files | `/fileresources` |
| Resources | Style Sheets | `/stylesheets` |
| Resources | Themes | `/themes` |
| Resources | About Document | `/about` (singleton) |
| Resources | Using Document | `/using` (singleton) |
| XPages | XPages | `/xpages` (named — list/create + `/{name}` get/update/delete; not a singleton). `POST /xpages` also ensures project skeleton. |
| Custom Controls | Custom Controls | `/customcontrols` (`.xsp` notes without XPage `K` flag; not Composite Components) |
| (XPages project) | Bootstrap skeleton | `POST /xpagesproject` — create-if-absent `plugin.xml`, `.project`, `.classpath`, `build.properties`, `faces-config`, Activator, `xsp.properties` |
| (XPages project) | Other `WEB-INF/` notes | **Bulk only** — type `xpagesresource` |
| Shared Elements | Navigators | `/navigators` |
| Code | Web Service Providers | `/webservices` + `GET /defaults/webservice` |
| Code | Web Service Consumers | `/webserviceconsumers` + `GET /defaults/webserviceconsumer` |
| Code | Java (class/interface/enum) | `/javasources` — kinds `class` \| `interface` \| `enum` |
| Code | Jars | `/jars` |
| Data | Data Connections | `/dataconnections` |
| Resources | Applets | `/applets` |
| Composite Applications | Wiring Properties | `/wiringproperties` |
| Composite Applications | Applications | `/compositeapplications` |
| Composite Applications | Components | `/compositecomponents` |
| Database | ACL | `/acl` (singleton; also in bulk) |

Singletons: most use `GET` / `POST` (create) / `PUT` (update) / `DELETE` on the collection path. Exceptions: **ACL** (GET/PUT, no DELETE); **Application Properties** and **`$DBIcon`** (GET/PUT only — always present with the NSF, no create/DELETE).

### Not included (CRUD or bulk)

| Designer section | Element | Reason |
|------------------|---------|--------|
| (residual) | Untitled / unclassified design notes | Catch-all bulk type `designnote` only |

### KEEP ACL (endpoint access)

| ACL | Operations |
|-----|------------|
| Reader | list, get |
| Designer | create, update, delete (ACL PUT requires Manager) |

## Endpoints

Named types share the same CRUD shape: **list** `GET /{types}`, **create** `POST /{types}` (optional `?name=`; empty body + `?kind=&name=` for kinded types), **get/update/delete** on `/{types}/{name}`.

### Pages

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/pages?nsfPath=` |
| POST | `/api/kyber-v1/pages?nsfPath=` (`text/xml` page DXL; optional `?name=`) |
| GET | `/api/kyber-v1/pages/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/pages/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/pages/{name}?nsfPath=` |

### Outlines

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/outlines?nsfPath=` |
| POST | `/api/kyber-v1/outlines?nsfPath=` (`text/xml` outline DXL; optional `?name=`) |
| GET | `/api/kyber-v1/outlines/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/outlines/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/outlines/{name}?nsfPath=` |

### Framesets

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/framesets?nsfPath=` |
| POST | `/api/kyber-v1/framesets?nsfPath=` (`text/xml` frameset DXL; optional `?name=`) |
| GET | `/api/kyber-v1/framesets/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/framesets/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/framesets/{name}?nsfPath=` |

### Forms

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/forms?nsfPath=` |
| POST | `/api/kyber-v1/forms?nsfPath=` (`text/xml` form DXL; optional `?name=`) |
| GET | `/api/kyber-v1/forms/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/forms/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/forms/{name}?nsfPath=` |

### Views

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/views?nsfPath=` |
| POST | `/api/kyber-v1/views?nsfPath=` (`text/xml` view DXL; optional `?name=`) |
| GET | `/api/kyber-v1/views/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/views/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/views/{name}?nsfPath=` |

### Folders

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/folders?nsfPath=` |
| POST | `/api/kyber-v1/folders?nsfPath=` (`text/xml` folder DXL; optional `?name=`) |
| GET | `/api/kyber-v1/folders/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/folders/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/folders/{name}?nsfPath=` |

### Subforms

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/subforms?nsfPath=` |
| POST | `/api/kyber-v1/subforms?nsfPath=` (`text/xml` subform DXL; optional `?name=`) |
| GET | `/api/kyber-v1/subforms/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/subforms/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/subforms/{name}?nsfPath=` |

### Shared fields

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/sharedfields?nsfPath=` |
| POST | `/api/kyber-v1/sharedfields?nsfPath=` (`text/xml` sharedfield DXL; optional `?name=`) |
| GET | `/api/kyber-v1/sharedfields/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/sharedfields/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/sharedfields/{name}?nsfPath=` |

### Shared columns

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/sharedcolumns?nsfPath=` |
| POST | `/api/kyber-v1/sharedcolumns?nsfPath=` (`text/xml` sharedcolumn DXL; optional `?name=`) |
| GET | `/api/kyber-v1/sharedcolumns/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/sharedcolumns/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/sharedcolumns/{name}?nsfPath=` |

### Images

`$DBIcon` is excluded — use `/dbicon`.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/images?nsfPath=` |
| POST | `/api/kyber-v1/images?nsfPath=` (`text/xml` imageresource DXL; optional `?name=`) |
| GET | `/api/kyber-v1/images/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/images/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/images/{name}?nsfPath=` |

### Files

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/fileresources?nsfPath=` |
| POST | `/api/kyber-v1/fileresources?nsfPath=` (`text/xml` fileresource DXL; optional `?name=`) |
| GET | `/api/kyber-v1/fileresources/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/fileresources/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/fileresources/{name}?nsfPath=` |

### Style sheets

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/stylesheets?nsfPath=` |
| POST | `/api/kyber-v1/stylesheets?nsfPath=` (`text/xml` stylesheetresource DXL; optional `?name=`) |
| GET | `/api/kyber-v1/stylesheets/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/stylesheets/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/stylesheets/{name}?nsfPath=` |

### Themes

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/themes?nsfPath=` |
| POST | `/api/kyber-v1/themes?nsfPath=` (`text/xml`; prefer Designer-exported DXL; optional `?name=`) |
| GET | `/api/kyber-v1/themes/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/themes/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/themes/{name}?nsfPath=` |

### Navigators

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/navigators?nsfPath=` |
| POST | `/api/kyber-v1/navigators?nsfPath=` (`text/xml`; optional `?name=`) |
| GET | `/api/kyber-v1/navigators/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/navigators/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/navigators/{name}?nsfPath=` |

### ACL

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/acl?nsfPath=` |
| PUT | `/api/kyber-v1/acl?nsfPath=` (`text/xml` `<acl>` DXL; requires Manager) |

### About Document

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/about?nsfPath=` |
| POST | `/api/kyber-v1/about?nsfPath=` (`text/xml` helpaboutdocument DXL; empty → starter; **409** if exists) |
| PUT | `/api/kyber-v1/about?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/about?nsfPath=` |

### Using Document

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/using?nsfPath=` |
| POST | `/api/kyber-v1/using?nsfPath=` (`text/xml` helpusingdocument DXL; empty → starter; **409** if exists) |
| PUT | `/api/kyber-v1/using?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/using?nsfPath=` |

### Agents

Kinds: `lotusscript` (default), `formula`, `simpleaction`, `java`, `importedjava`.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/agents?nsfPath=` |
| POST | `/api/kyber-v1/agents?nsfPath=` (`text/xml` agent DXL of any kind; optional `?name=`) |
| POST | `/api/kyber-v1/agents?nsfPath=&kind=&name=` (empty body → create from starter; **409** if exists) |
| GET | `/api/kyber-v1/defaults/agent?kind=` |
| GET | `/api/kyber-v1/agents/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/agents/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/agents/{name}?nsfPath=` |

### Script libraries

Kinds: `lotusscript` (default), `javascript` (client), `ssjs` (server JS), `java`. Web Service Consumers stay on `/webserviceconsumers`.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/scriptlibraries?nsfPath=` |
| POST | `/api/kyber-v1/scriptlibraries?nsfPath=` (`text/xml` scriptlibrary DXL of any kind; optional `?name=`) |
| POST | `/api/kyber-v1/scriptlibraries?nsfPath=&kind=&name=` (empty body → create from starter; **409** if exists) |
| GET | `/api/kyber-v1/defaults/scriptlibrary?kind=` |
| GET | `/api/kyber-v1/scriptlibraries/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/scriptlibraries/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/scriptlibraries/{name}?nsfPath=` |

### Java sources

Kinds: `class` (default), `interface`, `enum`.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/javasources?nsfPath=` |
| POST | `/api/kyber-v1/javasources?nsfPath=` (`text/xml`; optional `?name=`) |
| POST | `/api/kyber-v1/javasources?nsfPath=&kind=&name=` (empty body → create from starter; **409** if exists) |
| GET | `/api/kyber-v1/defaults/javasource?kind=` |
| GET | `/api/kyber-v1/javasources/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/javasources/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/javasources/{name}?nsfPath=` |

### Shared Actions

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/sharedactions?nsfPath=` |
| POST | `/api/kyber-v1/sharedactions?nsfPath=` (`text/xml` sharedactions DXL; optional `?name=`) |
| GET | `/api/kyber-v1/sharedactions/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/sharedactions/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/sharedactions/{name}?nsfPath=` |

### Database Script

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/databasescript?nsfPath=` |
| POST | `/api/kyber-v1/databasescript?nsfPath=` (`text/xml`; empty → starter; **409** if exists) |
| PUT | `/api/kyber-v1/databasescript?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/databasescript?nsfPath=` |

### Application Properties

Always present with the NSF — update only.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/applicationproperties?nsfPath=` |
| PUT | `/api/kyber-v1/applicationproperties?nsfPath=` (`text/xml`; **404** if missing) |

### Database icon (`$DBIcon`)

Always present with the NSF — update only. Not listed under `/images`.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/dbicon?nsfPath=` |
| PUT | `/api/kyber-v1/dbicon?nsfPath=` (`text/xml` imageresource; **404** if missing) |

### Xsp Properties

Optional `WEB-INF/xsp.properties` (not always present on classic NSF).

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/xspproperties?nsfPath=` |
| POST | `/api/kyber-v1/xspproperties?nsfPath=` (`text/xml`; empty → starter; **409** if exists) |
| PUT | `/api/kyber-v1/xspproperties?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/xspproperties?nsfPath=` |

### XPages

Named type (many per NSF) — same CRUD shape as forms/pages, not a singleton. Creating an XPage also ensures a minimal XPages project skeleton when missing (`POST /xpagesproject`).

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/xpages?nsfPath=` |
| POST | `/api/kyber-v1/xpages?nsfPath=` (`text/xml`; prefer Designer-exported DXL; optional `?name=`; empty body + `?name=` → starter; auto-ensures project) |
| GET | `/api/kyber-v1/xpages/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/xpages/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/xpages/{name}?nsfPath=` |

### XPages project bootstrap

For NSFs that never had XPages — creates missing project files only (idempotent).

| Method | Path |
|--------|------|
| POST | `/api/kyber-v1/xpagesproject?nsfPath=` (no body; returns `created` / `skipped`) |

Skeleton: `.project`, `.classpath`, `plugin.xml`, `build.properties`, `WEB-INF/faces-config.xml`, `WEB-INF/classes/plugin/Activator.class`, `WEB-INF/xsp.properties` (embedded from a known-good Designer export; Domino CD `$FileData` is not rewritten). Designer writes `META-INF/MANIFEST.MF` and `Local/plugin/Activator.java` itself when the application is opened.

### Custom Controls

XPages Custom Controls (Designer "Custom Controls") — `.xsp` design notes with `$Flags` like `gC~4;` (no `K`). Not the same as Composite Components (bulk type `compositecomponent`).

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/customcontrols?nsfPath=` |
| POST | `/api/kyber-v1/customcontrols?nsfPath=` (`text/xml`; Designer-exported DXL; optional `?name=`) |
| GET | `/api/kyber-v1/customcontrols/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/customcontrols/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/customcontrols/{name}?nsfPath=` |

## Build

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

**Output:** `target/infoware-kyber.jar` → `$env:DRAPI_HOME\libs\`.

Restart: Notes COM `tell restapi quit` then `load restapi` (or `tools/restart-restapi-com.ps1` / `build.ps1 -InstallToDrapi`). Do not rely on `tell restapi reload` when swapping JARs.

## Tests

Unit tests (JUnit 5, no Domino) live under `extension/src/test/java`. Covered: DXL helpers, kind adapters, classpath starters, alphabetical NSF pick helper, blank baseline resources, OpenAPI shape (incl. always-present GET/PUT-only).

```powershell
cd extension
.\build.ps1   # runs mvn package (includes tests)
# or:
mvn test
```

Live smoke against DRAPI. `tools/smoke-kyber-api.ps1` creates `kybertest.nsf` and runs list/create/get/put/delete for every typed CRUD collection (including agent, script-library and Java-source kinds), plus singleton create/update and get/put for application properties, the database icon and the ACL. Each created element is written again with `Hello World` and read back. LotusScript, formula and simple-action agents are then run, and each run writes `Hello World` onto a document. The LotusScript agent calls `SmokeLibLs`. Deleted elements are created again so the NSF stays populated. Domino URL commands then open the database, form, page, about document, using document, view, folder, navigator, frameset, icon, image, file, stylesheet, agents, web service and XPage. `?CreateDocument` saves a document that the view, `?OpenDocument` and `?EditDocument` show as `Hello World`, and `?DeleteDocument` removes it:

```powershell
.\tools\smoke-kyber-api.ps1
# default NSF: kybertest.nsf  (optional: -NsfPath other.nsf)
```

Note: KEEP often maps `KeepExceptionNotFound` to HTTP **400** (message still says “not found”); OpenAPI documents 404. The smoke accepts either.

## Manual test

Use your REST base URL (see `.env.example` / `.env.drapi`).

```powershell
$base = "https://your-host:8880"
$nsf = "apps/mydb.nsf"

$auth = Invoke-RestMethod -Method POST -Uri "$base/api/v1/auth" `
    -ContentType "application/json" `
    -Body '{"username":"user","password":"***"}'
$token = $auth.bearer
$headers = @{ Authorization = "Bearer $token" }

# Form
$formDxl = @"
<?xml version='1.0'?>
<!DOCTYPE form SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<form name='HelloForm' xmlns='http://www.lotus.com/dxl' version='14.5'
 publicaccess='false' designerversion='8.5.3' renderpassthrough='true'>
<body><richtext>
<pardef id='1'/>
<par def='1'><field type='text' kind='editable' name='Subject'/></par>
</richtext></body>
<item name='$$ScriptName' summary='false' sign='true'><text>HelloForm</text></item>
</form>
"@
Invoke-RestMethod -Method POST -Uri "$base/api/kyber-v1/forms?nsfPath=$nsf" `
    -Headers $headers -ContentType "text/xml" -Body $formDxl

# View
$viewDxl = @"
<?xml version='1.0'?>
<!DOCTYPE view SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
<view name='HelloView' xmlns='http://www.lotus.com/dxl' version='14.5'
 showinmenu='true' publicaccess='false' designerversion='8.5.3'
 unreadmarks='none' onopengoto='lastopened' onrefresh='displayindicator'>
<code event='selection'><formula>SELECT Form = "HelloForm"</formula></code>
<column itemname='Subject' width='20' resizable='true'>
<columnheader title='Subject'><font size='9pt'/></columnheader></column>
</view>
"@
Invoke-RestMethod -Method POST -Uri "$base/api/kyber-v1/views?nsfPath=$nsf" `
    -Headers $headers -ContentType "text/xml" -Body $viewDxl
```

## Layout

```text
extension/
  src/main/java/se/infoware/domino/kyber/
    dbrequests/design/       per-type CRUD + shared DesignDxl / DesignCrud
    handlers/design/         AbstractImportDxlHandler + Create*/Update* for text/xml body
  src/main/resources/
    config/config.json       # versions.kyber → route /api/kyber-v1 + openapi.kyber.json
                             # tags design-element + design-handler (not tag "design" — avoids HCL Setup clash)
    openapi/openapi.kyber.json
    kyber.properties         # kyber.defaultNsf=kyber.nsf
    defaults/                # embedded starter DXL (+ blank/ baseline)
```

## Adding another design type

1. OpenAPI paths + `operationId`s (`listX`, `getX`, `createX`, `updateX`, `deleteX`) under tag **`design-element`**; `/defaults*` and bulk `/designdxl` under **`design-handler`**
2. `dbrequests/design` handlers (JNX list/get/delete, DXL import via `DesignCrud` / `DesignDxl`)
3. `handlers/design/CreateX` and `UpdateX` extending `AbstractImportDxlHandler` for `text/xml` **POST** (create) and **PUT** (update)
4. Document the type in this README table

Do **not** use OpenAPI tag `design` — that maps HCL Setup's Design verticle package. The catalog version id must stay **`kyber`** (see `config.json`).