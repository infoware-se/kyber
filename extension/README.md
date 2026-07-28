# iws-design (Java KEEP extension)

**Custom KEEP extension — Infoware, not HCL.**

Manage database **design elements** via Domino REST API. Target database is selected with query param **`nsfPath`**. Create/update uses raw **typed DXL** (`text/xml`), similar to Setup `PUT /api/setup-v1/dxl` but scoped per element type (rejects wrong root elements). Forms and views are included so you do not need Setup’s simplified JSON create for those.

## Supported types

| Type | List | Create/replace | Get | Delete | DXL root |
|------|------|----------------|-----|--------|----------|
| Page | `GET /pagenames` | `PUT /pages` | `GET /pages/{name}` | `DELETE /pages/{name}` | `<page name="…">` |
| Outline | `GET /outlinenames` | `PUT /outlines` | `GET /outlines/{name}` | `DELETE /outlines/{name}` | `<outline name="…">` |
| Frameset | `GET /framesetnames` | `PUT /framesets` | `GET /framesets/{name}` | `DELETE /framesets/{name}` | `<frameset name="…">` |
| Form | `GET /formnames` | `PUT /forms` | `GET /forms/{name}` | `DELETE /forms/{name}` | `<form name="…">` |
| View | `GET /viewnames` | `PUT /views` | `GET /views/{name}` | `DELETE /views/{name}` | `<view name="…">` |
| Folder | `GET /foldernames` | `PUT /folders` | `GET /folders/{name}` | `DELETE /folders/{name}` | `<folder name="…">` |
| Subform | `GET /subformnames` | `PUT /subforms` | `GET /subforms/{name}` | `DELETE /subforms/{name}` | `<subform name="…">` |
| Shared field | `GET /sharedfieldnames` | `PUT /sharedfields` | `GET /sharedfields/{name}` | `DELETE /sharedfields/{name}` | `<sharedfield name="…">` |
| Shared column | `GET /sharedcolumnnames` | `PUT /sharedcolumns` | `GET /sharedcolumns/{name}` | `DELETE /sharedcolumns/{name}` | `<sharedcolumn name="…">` |
| Image | `GET /imagenames` | `PUT /images` | `GET /images/{name}` | `DELETE /images/{name}` | `<imageresource name="…">` |
| File | `GET /fileresourcenames` | `PUT /fileresources` | `GET /fileresources/{name}` | `DELETE /fileresources/{name}` | `<fileresource name="…">` |
| Style sheet | `GET /stylesheetnames` | `PUT /stylesheets` | `GET /stylesheets/{name}` | `DELETE /stylesheets/{name}` | `<stylesheetresource name="…">` |
| Theme | `GET /themenames` | `PUT /themes` | `GET /themes/{name}` | `DELETE /themes/{name}` | `<theme>` / note-form |
| About Document | — | `PUT /about` | `GET /about` | `DELETE /about` | `<helpaboutdocument>` |
| Using Document | — | `PUT /using` | `GET /using` | `DELETE /using` | `<helpusingdocument>` |
| Agent | `GET /agentnames` | `PUT /agents` | `GET /agents/{name}` | `DELETE /agents/{name}` | `<agent name="…">` |
| Script library | `GET /scriptlibrarynames` | `PUT /scriptlibraries` | `GET /scriptlibraries/{name}` | `DELETE /scriptlibraries/{name}` | `<scriptlibrary name="…">` |
| Shared Actions | — | `PUT /sharedactions` | `GET /sharedactions` | `DELETE /sharedactions` | `<sharedactions>` |
| Database Script | — | `PUT /databasescript` | `GET /databasescript` | `DELETE /databasescript` | `<databasescript>` |
| Application Properties | — | `PUT /applicationproperties` | `GET /applicationproperties` | — (no delete) | `<note class="icon">` / `<database>` |
| XPage | `GET /xpagenames` | `PUT /xpages` | `GET /xpages/{name}` | `DELETE /xpages/{name}` | `<note>` / `<fileresource>` |
| Wiring Properties | `GET /wiringpropertiesnames` | `PUT /wiringproperties` | `GET /wiringproperties/{name}` | `DELETE /wiringproperties/{name}` | `<note>` / `<fileresource>` |
| Composite Application | `GET /compositeapplicationnames` | `PUT /compositeapplications` | `GET /compositeapplications/{name}` | `DELETE /compositeapplications/{name}` | `<note>` / `<fileresource>` |
| Composite Component | `GET /compositecomponentnames` | `PUT /compositecomponents` | `GET /compositecomponents/{name}` | `DELETE /compositecomponents/{name}` | `<note>` / `<fileresource>` |

All paths are under `/api/design-v1` and require `?nsfPath=`.

## Coverage (Domino Designer)

Mapped to the Designer outline. “In” = typed DXL CRUD in this extension (v0.11).

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
| Code | Agents | `/agents` |
| Code | Shared Actions | `/sharedactions` (singleton) |
| Code | Script Libraries | `/scriptlibraries` (LS, JS, SSJS, **Java** libs) |
| Code | Database Script | `/databasescript` (singleton) |
| Resources | Images | `/images` |
| Resources | Files | `/fileresources` |
| Resources | Style Sheets | `/stylesheets` |
| Resources | Themes | `/themes` |
| Resources | About Document | `/about` (singleton) |
| Resources | Using Document | `/using` (singleton) |
| Application Configuration | Application Properties | `/applicationproperties` (GET/PUT; no DELETE) |
| XPages | XPages | `/xpages` |
| Composite Applications | Wiring Properties | `/wiringproperties` |
| Composite Applications | Applications | `/compositeapplications` |
| Composite Applications | Components | `/compositecomponents` |

Singletons have no list/`{name}` path — only `GET` / `PUT` / `DELETE` on the collection path.

### Not included

| Designer section | Element | Reason |
|------------------|---------|--------|
| Shared Elements | Navigators | Removed — Domino 14.5 has no first-class `<navigator>` DXL; create was unreliable |
| Custom Controls | Custom Controls | No first-class JNX type (different `$Flags` than XPage) |
| Code | Web Service Providers | No first-class JNX type (DXL has `webservice` only) |
| Code | Web Service Consumers | No first-class JNX type |
| Code | Java (separate node) | Same notes as Java **script libraries** → use `/scriptlibraries` |
| Code | Jars | No standalone JNX type (jars embed on Java agents/libs) |
| Resources | Applets | No first-class JNX type |
| Resources | Icon | Same note as Application Properties (`/applicationproperties`). Always present with the NSF — **GET/PUT only, never DELETE** |
| Data | Data Connections | No first-class JNX type |
| Application Configuration | Xsp Properties | No first-class typed DXL CRUD in this extension |

| ACL | Operations |
|-----|------------|
| Reader | list, get |
| Designer | create/replace, delete |

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

| Method | Path |
|--------|------|
| GET | `/api/design-v1/agentnames?nsfPath=` |
| PUT | `/api/design-v1/agents?nsfPath=` (`text/xml` agent DXL) |
| GET | `/api/design-v1/agents/{name}?nsfPath=` |
| DELETE | `/api/design-v1/agents/{name}?nsfPath=` |

### Script libraries

| Method | Path |
|--------|------|
| GET | `/api/design-v1/scriptlibrarynames?nsfPath=` |
| PUT | `/api/design-v1/scriptlibraries?nsfPath=` (`text/xml` scriptlibrary DXL) |
| GET | `/api/design-v1/scriptlibraries/{name}?nsfPath=` |
| DELETE | `/api/design-v1/scriptlibraries/{name}?nsfPath=` |

### Shared Actions

| Method | Path |
|--------|------|
| GET | `/api/design-v1/sharedactions?nsfPath=` |
| PUT | `/api/design-v1/sharedactions?nsfPath=` (`text/xml` sharedactions DXL) |
| DELETE | `/api/design-v1/sharedactions?nsfPath=` |

### Database Script

| Method | Path |
|--------|------|
| GET | `/api/design-v1/databasescript?nsfPath=` |
| PUT | `/api/design-v1/databasescript?nsfPath=` (`text/xml` databasescript DXL) |
| DELETE | `/api/design-v1/databasescript?nsfPath=` |

### Application Properties

| Method | Path |
|--------|------|
| GET | `/api/design-v1/applicationproperties?nsfPath=` |
| PUT | `/api/design-v1/applicationproperties?nsfPath=` (`text/xml` icon note or `<database>` DXL) |

No DELETE — Domino always creates the icon/Application Properties note with the NSF; it must remain.

### XPages

| Method | Path |
|--------|------|
| GET | `/api/design-v1/xpagenames?nsfPath=` |
| PUT | `/api/design-v1/xpages?nsfPath=` (`text/xml`; prefer Designer-exported DXL) |
| GET | `/api/design-v1/xpages/{name}?nsfPath=` |
| DELETE | `/api/design-v1/xpages/{name}?nsfPath=` |

Custom Controls are not supported.

### Wiring Properties

| Method | Path |
|--------|------|
| GET | `/api/design-v1/wiringpropertiesnames?nsfPath=` |
| PUT | `/api/design-v1/wiringproperties?nsfPath=` |
| GET | `/api/design-v1/wiringproperties/{name}?nsfPath=` |
| DELETE | `/api/design-v1/wiringproperties/{name}?nsfPath=` |

### Composite Applications

| Method | Path |
|--------|------|
| GET | `/api/design-v1/compositeapplicationnames?nsfPath=` |
| PUT | `/api/design-v1/compositeapplications?nsfPath=` |
| GET | `/api/design-v1/compositeapplications/{name}?nsfPath=` |
| DELETE | `/api/design-v1/compositeapplications/{name}?nsfPath=` |

### Composite Components

| Method | Path |
|--------|------|
| GET | `/api/design-v1/compositecomponentnames?nsfPath=` |
| PUT | `/api/design-v1/compositecomponents?nsfPath=` |
| GET | `/api/design-v1/compositecomponents/{name}?nsfPath=` |
| DELETE | `/api/design-v1/compositecomponents/{name}?nsfPath=` |

## Build

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

**Output:** `target/iws-design-*.jar` → `$env:DRAPI_HOME\libs\`.

Restart: `tell restapi quit`, then `load restapi` on the Domino console (`load` cannot be done via management after quit).

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
  src/main/java/se/infoware/domino/keep/
    dbrequests/design/       per-type CRUD + shared DesignDxl / DxlDesignPatch
    handlers/design/         AbstractImportDxlHandler + Create{Page,Outline,Frameset,Form,View,Folder}
  src/main/resources/
    config/config.json       # OpenAPI tag iws-design (not design — avoids HCL Setup clash)
    openapi/openapi.design.json
```

## Adding another design type

1. OpenAPI paths + `operationId`s (`getXNames`, `getX`, `createX`, `deleteX`) under tag **`iws-design`**
2. `dbrequests/design` handlers (JNX `getX` / `getXs` / `delete`, DXL import via `DesignDxl.requireRootName(dxl, "root")`)
3. `handlers/design/CreateX extends AbstractImportDxlHandler` for `text/xml` PUT
4. Document the type in this README table

Do **not** use OpenAPI tag `design` — that maps HCL Setup’s Design verticle package.
