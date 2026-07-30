# keep-design (Java KEEP extension)

**Custom KEEP extension â€” KEEP, not HCL.**

Manage database **design elements** via Domino REST API. Target database is selected with query param **`nsfPath`**. **Create** is `POST` (empty body → starter DXL; **409** if exists). **Update** is `PUT /{type}/{name}` or `PUT` on singleton paths (**404** if missing). Collection `PUT` upsert remains for named types but is **deprecated**. Responses include exported `dxl` after write.

## Supported types

| Type | List | Create | Update | Get | Delete |
|------|------|--------|--------|-----|--------|
| Named (form, view, page, …) | `GET /{type}names` | `POST /{types}?name=` | `PUT /{types}/{name}` | `GET /{types}/{name}` | `DELETE /{types}/{name}` |
| About / Using / Database Script | — | `POST /about` etc. | `PUT /about` etc. | `GET` | `DELETE` |
| Xsp Properties | — | `POST /xspproperties` | `PUT /xspproperties` | `GET` | `DELETE` |
| DB icon (`$DBIcon`) | — | `POST /dbicon` | `PUT /dbicon` | `GET /dbicon` | `DELETE /dbicon` |
| Application Properties / ACL | — | — | `PUT` | `GET` | ACL: — / App props: — |

`$DBIcon` is excluded from `/imagenames` — use `/dbicon`. Kinded types (`agent`, `scriptlibrary`, `javasource`) take `?kind=` + `?name=` on empty-body `POST`.

All paths are under `/api/design-v1` and require `?nsfPath=`.

## Bulk export/import (`design-handler`)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/designdxl?nsfPath=` | Export all supported elements as `{ nsfPath, elements: [{ type, name?, dxl }] }` |
| PUT | `/designdxl?nsfPath=` | Import that array; **create NSF if missing**; same import/sign as per-type `Create*` |

Swagger tag: **`design-handler`** for `/defaults`, `/defaults/{type}`, and `/designdxl` (per-type CRUD stays under **`design-element`**).

Element `type` values match repo layout (`form`, `about`, `image`, â€¦). Bridge scripts in repo root [`tools/`](../tools/):

```powershell
.\tools\export-to-repo.ps1 -RestUrl https://host:8880 -NsfPath test/app.nsf -OutDir .\design
# Java in script libraries, agents, applets: strips <javaresource> Base64 and writes sibling .java (default).
# Keep compiled class in DXL: -ReadableJava:$false
.\tools\import-from-repo.ps1 -RestUrl https://host:8880 -NsfPath test/restored.nsf -InDir .\design
```

Repo tree (folders = DXL `<!DOCTYPE â€¦>` name):

```
design/
  manifest.json          # type (API), doctype, path, name?
  form/Main.dxl
  view/($All).dxl
  imageresource/logo.gif.dxl
  helpaboutdocument/about.dxl
  ...
```

`import-from-repo.ps1` reads `manifest.json` (API `type` + path); folder names follow DOCTYPE.

After PUT, the API compiles LotusScript (script libraries first) and returns a `lotusscript` summary. Notes â€œError processing use list moduleâ€ after a restore usually means code was not compiled; re-import with 0.12+ or run Designer **Recompile All LotusScript**. Some complex libraries (e.g. DDM) can still report syntax errors after DXL round-trip â€” open those in Designer if needed.

**Bulk-only types** (no list/get/put/delete endpoints; only in `/designdxl`): `xpagesresource` (incl. `WEB-INF/` except `xsp.properties`, i18n `*.properties`, `.settings/`, `icons/`, `Local/`), and catch-all `designnote` for any other remaining design note (UNID-deduped after typed exporters). Starters: `GET /defaults/{type}` for `xpagesresource` (not `designnote`).

**Blank NSF baseline** (reference DXL, not starters): [`resources/defaults/blank/`](src/main/resources/defaults/blank/) — `acl.dxl`, `applicationproperties.dxl`, `image-DBIcon.dxl`, `view-default.dxl`, plus `manifest.json` from a Domino blank database.

## Coverage (Domino Designer)

Mapped to the Designer outline. â€œInâ€ = typed DXL CRUD; â€œBulkâ€ = `/designdxl` only (v0.16).

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
| Code | Agents | `/agents` — kinds `lotusscript` \| `formula` \| `simpleaction` \| `java` |
| Code | Shared Actions | `/sharedactions/{name}` |
| Code | Script Libraries | `/scriptlibraries` — kinds `lotusscript` \| `javascript` \| `ssjs` \| `java` (defaults + empty-body create) |
| Code | Database Script | `/databasescript` (singleton) |
| Application Configuration | Application Properties | `/applicationproperties` (singleton; GET/PUT, no DELETE) |
| Application Configuration | Xsp Properties | `/xspproperties` (singleton; GET/PUT, no DELETE; `WEB-INF/xsp.properties`) |
| Resources | Images | `/images` |
| Resources | Files | `/fileresources` |
| Resources | Style Sheets | `/stylesheets` |
| Resources | Themes | `/themes` |
| Resources | About Document | `/about` (singleton) |
| Resources | Using Document | `/using` (singleton) |
| XPages | XPages | `/xpages` |
| Custom Controls | Custom Controls | `/customcontrols` (`.xsp` notes without XPage `K` flag; not Composite Components) |
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

Singletons have no list/`{name}` path — only `GET` / `PUT` / `DELETE` on the collection path (ACL has no DELETE).

### Not included (CRUD or bulk)

| Designer section | Element | Reason |
|------------------|---------|--------|
| (residual) | Untitled / unclassified design notes | Catch-all bulk type `designnote` only |

### KEEP ACL (endpoint access)

| ACL | Operations |
|-----|------------|
| Reader | list, get |
| Designer | create/replace, delete (ACL PUT requires Manager) |

## Endpoints

### Pages

| Method | Path |
|--------|------|
| GET | `/api/design-v1/pagenames?nsfPath=` |
| PUT | `/api/design-v1/pages?nsfPath=` (`text/xml` page DXL) |
| GET | `/api/design-v1/pages/{name}?nsfPath=` |
| DELETE | `/api/design-v1/pages/{name}?nsfPath=` |

### Outlines

| Method | Path |
|--------|------|
| GET | `/api/design-v1/outlinenames?nsfPath=` |
| PUT | `/api/design-v1/outlines?nsfPath=` (`text/xml` outline DXL) |
| GET | `/api/design-v1/outlines/{name}?nsfPath=` |
| DELETE | `/api/design-v1/outlines/{name}?nsfPath=` |

### Framesets

| Method | Path |
|--------|------|
| GET | `/api/design-v1/framesetnames?nsfPath=` |
| PUT | `/api/design-v1/framesets?nsfPath=` (`text/xml` frameset DXL) |
| GET | `/api/design-v1/framesets/{name}?nsfPath=` |
| DELETE | `/api/design-v1/framesets/{name}?nsfPath=` |

### Forms

| Method | Path |
|--------|------|
| GET | `/api/design-v1/formnames?nsfPath=` |
| PUT | `/api/design-v1/forms?nsfPath=` (`text/xml` form DXL) |
| GET | `/api/design-v1/forms/{name}?nsfPath=` |
| DELETE | `/api/design-v1/forms/{name}?nsfPath=` |

### Views

| Method | Path |
|--------|------|
| GET | `/api/design-v1/viewnames?nsfPath=` |
| PUT | `/api/design-v1/views?nsfPath=` (`text/xml` view DXL) |
| GET | `/api/design-v1/views/{name}?nsfPath=` |
| DELETE | `/api/design-v1/views/{name}?nsfPath=` |

### Folders

| Method | Path |
|--------|------|
| GET | `/api/design-v1/foldernames?nsfPath=` |
| PUT | `/api/design-v1/folders?nsfPath=` (`text/xml` folder DXL) |
| GET | `/api/design-v1/folders/{name}?nsfPath=` |
| DELETE | `/api/design-v1/folders/{name}?nsfPath=` |

### Subforms

| Method | Path |
|--------|------|
| GET | `/api/design-v1/subformnames?nsfPath=` |
| PUT | `/api/design-v1/subforms?nsfPath=` (`text/xml` subform DXL) |
| GET | `/api/design-v1/subforms/{name}?nsfPath=` |
| DELETE | `/api/design-v1/subforms/{name}?nsfPath=` |

### Shared fields

| Method | Path |
|--------|------|
| GET | `/api/design-v1/sharedfieldnames?nsfPath=` |
| PUT | `/api/design-v1/sharedfields?nsfPath=` (`text/xml` sharedfield DXL) |
| GET | `/api/design-v1/sharedfields/{name}?nsfPath=` |
| DELETE | `/api/design-v1/sharedfields/{name}?nsfPath=` |

### Shared columns

| Method | Path |
|--------|------|
| GET | `/api/design-v1/sharedcolumnnames?nsfPath=` |
| PUT | `/api/design-v1/sharedcolumns?nsfPath=` (`text/xml` sharedcolumn DXL) |
| GET | `/api/design-v1/sharedcolumns/{name}?nsfPath=` |
| DELETE | `/api/design-v1/sharedcolumns/{name}?nsfPath=` |

### Images

| Method | Path |
|--------|------|
| GET | `/api/design-v1/imagenames?nsfPath=` |
| PUT | `/api/design-v1/images?nsfPath=` (`text/xml` imageresource DXL) |
| GET | `/api/design-v1/images/{name}?nsfPath=` |
| DELETE | `/api/design-v1/images/{name}?nsfPath=` |

### Files

| Method | Path |
|--------|------|
| GET | `/api/design-v1/fileresourcenames?nsfPath=` |
| PUT | `/api/design-v1/fileresources?nsfPath=` (`text/xml` fileresource DXL) |
| GET | `/api/design-v1/fileresources/{name}?nsfPath=` |
| DELETE | `/api/design-v1/fileresources/{name}?nsfPath=` |

### Style sheets

| Method | Path |
|--------|------|
| GET | `/api/design-v1/stylesheetnames?nsfPath=` |
| PUT | `/api/design-v1/stylesheets?nsfPath=` (`text/xml` stylesheetresource DXL) |
| GET | `/api/design-v1/stylesheets/{name}?nsfPath=` |
| DELETE | `/api/design-v1/stylesheets/{name}?nsfPath=` |

### Themes

| Method | Path |
|--------|------|
| GET | `/api/design-v1/themenames?nsfPath=` |
| PUT | `/api/design-v1/themes?nsfPath=` (`text/xml`; prefer Designer-exported DXL) |
| GET | `/api/design-v1/themes/{name}?nsfPath=` |
| DELETE | `/api/design-v1/themes/{name}?nsfPath=` |


### ACL

| Method | Path |
|--------|------|
| GET | `/api/design-v1/acl?nsfPath=` |
| PUT | `/api/design-v1/acl?nsfPath=` (`text/xml` `<acl>` DXL; requires Manager) |

### About Document

| Method | Path |
|--------|------|
| GET | `/api/design-v1/about?nsfPath=` |
| PUT | `/api/design-v1/about?nsfPath=` (`text/xml` helpaboutdocument DXL) |
| DELETE | `/api/design-v1/about?nsfPath=` |

### Using Document

| Method | Path |
|--------|------|
| GET | `/api/design-v1/using?nsfPath=` |
| PUT | `/api/design-v1/using?nsfPath=` (`text/xml` helpusingdocument DXL) |
| DELETE | `/api/design-v1/using?nsfPath=` |

### Agents

Kinds: `lotusscript` (default), `formula`, `simpleaction`, `java`.

| Method | Path |
|--------|------|
| GET | `/api/design-v1/agentnames?nsfPath=` |
| PUT | `/api/design-v1/agents?nsfPath=` (`text/xml` agent DXL of any kind) |
| PUT | `/api/design-v1/agents?nsfPath=&kind=&name=` (empty body → create from starter) |
| GET | `/api/design-v1/defaults/agent?kind=` |
| GET | `/api/design-v1/agents/{name}?nsfPath=` |
| DELETE | `/api/design-v1/agents/{name}?nsfPath=` |

### Script libraries

Kinds: `lotusscript` (default), `javascript` (client), `ssjs` (server JS), `java`. Web Service Consumers stay on `/webserviceconsumers`.

| Method | Path |
|--------|------|
| GET | `/api/design-v1/scriptlibrarynames?nsfPath=` |
| PUT | `/api/design-v1/scriptlibraries?nsfPath=` (`text/xml` scriptlibrary DXL of any kind) |
| PUT | `/api/design-v1/scriptlibraries?nsfPath=&kind=&name=` (empty body → create from starter) |
| GET | `/api/design-v1/defaults/scriptlibrary?kind=` |
| GET | `/api/design-v1/scriptlibraries/{name}?nsfPath=` |
| DELETE | `/api/design-v1/scriptlibraries/{name}?nsfPath=` |

### Shared Actions

| Method | Path |
|--------|------|
| GET | `/api/design-v1/sharedactionnames?nsfPath=` |
| GET | `/api/design-v1/sharedactions/{name}?nsfPath=` |
| PUT | `/api/design-v1/sharedactions?nsfPath=` (`text/xml` sharedactions DXL) |
| DELETE | `/api/design-v1/sharedactions/{name}?nsfPath=` |

### Database Script

| Method | Path |
|--------|------|
| GET | `/api/design-v1/databasescript?nsfPath=` |
| PUT | `/api/design-v1/databasescript?nsfPath=` (`text/xml` databasescript DXL) |
| DELETE | `/api/design-v1/databasescript?nsfPath=` |


| Method | Path |
|--------|------|


### XPages

| Method | Path |
|--------|------|
| GET | `/api/design-v1/xpagenames?nsfPath=` |
| PUT | `/api/design-v1/xpages?nsfPath=` (`text/xml`; prefer Designer-exported DXL) |
| GET | `/api/design-v1/xpages/{name}?nsfPath=` |
| DELETE | `/api/design-v1/xpages/{name}?nsfPath=` |

### Custom Controls

XPages Custom Controls (Designer "Custom Controls") — `.xsp` design notes with `$Flags` like `gC~4;` (no `K`). Not the same as Composite Components (bulk type `compositecomponent`).

| Method | Path |
|--------|------|
| GET | `/api/design-v1/customcontrolnames?nsfPath=` |
| PUT | `/api/design-v1/customcontrols?nsfPath=` (`text/xml`; Designer-exported DXL) |
| GET | `/api/design-v1/customcontrols/{name}?nsfPath=` |
| DELETE | `/api/design-v1/customcontrols/{name}?nsfPath=` |

## Build

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

**Output:** `target/keep-design-*.jar` â†’ `$env:DRAPI_HOME\libs\`.

Restart: Notes COM `tell restapi quit` then `load restapi` (or `tools/restart-restapi-com.ps1` / `build.ps1 -InstallToDrapi`). Do not rely on `tell restapi reload` when swapping JARs.

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
Invoke-RestMethod -Method PUT -Uri "$base/api/design-v1/forms?nsfPath=$nsf" `
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
Invoke-RestMethod -Method PUT -Uri "$base/api/design-v1/views?nsfPath=$nsf" `
    -Headers $headers -ContentType "text/xml" -Body $viewDxl
```

## Layout

```text
extension/
  src/main/java/se/KEEP/domino/keep/
    dbrequests/design/       per-type CRUD + shared DesignDxl / DxlDesignPatch
    handlers/design/         AbstractImportDxlHandler + Create{Page,Outline,Frameset,Form,View,Folder}
  src/main/resources/
    config/config.json       # OpenAPI tags design-element + design-handler (not design — avoids HCL Setup clash)
    openapi/openapi.design.json
```

## Adding another design type

1. OpenAPI paths + `operationId`s (`getXNames`, `getX`, `createX`, `deleteX`) under tag **`design-element`**; `/defaults*` and bulk `/designdxl` under **`design-handler`**
2. `dbrequests/design` handlers (JNX `getX` / `getXs` / `delete`, DXL import via `DesignDxl.requireRootName(dxl, "root")`)
3. `handlers/design/CreateX extends AbstractImportDxlHandler` for `text/xml` PUT
4. Document the type in this README table

Do **not** use OpenAPI tag `design` â€” that maps HCL Setupâ€™s Design verticle package.
