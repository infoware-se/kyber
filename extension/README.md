# Kyber — Infoware REST API for design elements (`infoware-kyber.jar`)

Not an HCL product. Runs as a KEEP/DRAPI extension.

| | |
|--|--|
| **Version** | 0.24.13 |
| **API** | `/api/kyber-v1` |
| **Catalog id** | `kyber` (Admin UI / OpenAPI version key) |
| **Spec** | `openapi/openapi.kyber.json` |

Kyber is complete CRUD for the design elements in an NSF. The target database is the query param **`nsfPath`**.

The body of a create, an update, and a round trip is Domino DXL (`text/xml`). That XML is the format Designer already documents. Its grammar is the DTD shipped with Designer, `xmlschemas/domino_*.dtd`. A get also returns `code` when the note has one script or one text file, and that text can be written back. The stored design remains the DXL. A form has many events, so its scripts stay in the DXL.

`kyber.nsf` is the template library. The property `kyber.defaultNsf` names it and defaults to `kyber.nsf`. Put the design elements you want to reuse in that database. `GET /defaults/{type}` returns the first match by name, with `?kind=` for an agent, a script library, or a Java source. An empty create copies that element into the target NSF. When the template database has no such element, Kyber uses the starter embedded in the JAR under `resources/defaults/`. Kyber creates `kyber.nsf` on the first design write if the file is missing. The new file explains itself in About and Using and holds no templates until you add them. Those two documents, and the database icon, are not templates.

Standard collection CRUD:

| | Path | operationId |
|--|------|-------------|
| **List** | `GET /agents` | `listAgents` |
| **Create** | `POST /agents?name=` (empty → default; **409** if exists) | `createAgent` |
| **Get** | `GET /agents/{name}` | `getAgent` |
| **Update** | `PUT /agents/{name}` (**404** if missing) | `updateAgent` |
| **Delete** | `DELETE /agents/{name}` | `deleteAgent` |

Same pattern for other named types (`/forms`, `/scriptlibraries`, `/xpagesresources`, …). Write responses include exported `dxl`.

A list keeps the name array and adds `elements`: `name`, plus `fileName`, `aliases`, `kind` and `comment` when the note has them. `kind` is filled for agents, script libraries and Java sources. `aliases` is the part of the name after `|`, the name used by a URL or `OpenForm`. A get or write adds `code` when the note has readable LotusScript, JavaScript, Java, an agent formula, or a text file. Binary files such as `.class` and `.jar` stay in `dxl` only.

`code` can be written back on an agent, a script library, a Java source, a file resource, a style sheet, a WebContent file, or an XPages project file. Send `text/plain`, or JSON `{"code":"..."}`. The note keeps its other DXL. A form has several events, so its scripts stay in the DXL.

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

## Design export and design import (`design-handler`)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/defaults` | List the design templates |
| GET | `/defaults/{type}` | Get one design template. `kind` for an agent, a script library, or a Java source |
| GET | `/export?nsfPath=` | Design export of a database that already exists |
| PUT | `/import?nsfPath=&dbTitle=` | Design import. When the file is missing, checks Create databases & templates and creates the NSF |
Per-type CRUD stays under **`design-element`**.

**Templates** live in `kyber.nsf`, or whichever NSF `kyber.defaultNsf` names. `GET /defaults/{type}` returns the first element of that type, matched by case-insensitive name, and `?kind=` when the type has kinds. An empty create copies it into the target NSF. When the template database has no such element, Kyber uses the starter embedded in the JAR under `resources/defaults/`.

Element `type` is the API name (`form`, `about`, `image`, …). The folder is the Package Explorer name (`Forms`, `Resources`, `Resources/Images`, …). Bridge scripts in [`tools/`](../tools/):

```powershell
.\tools\export-to-repo.ps1 -RestUrl https://host:8880 -NsfPath test/app.nsf
# Writes .\domino\test\app.nsf-<replica id>\ in the current directory.
.\tools\export-to-repo.ps1 -RestUrl https://host:8880 -NsfPath test/app.nsf -OutDir D:\other-repo
# -OutDir is that repo's root. The NSF lands in D:\other-repo\domino\test\app.nsf-<replica id>\.
# A later export replaces that NSF folder and leaves other databases under domino\ alone.
.\tools\import-from-repo.ps1 -RestUrl https://host:8880 -NsfPath test/restored.nsf -InDir D:\other-repo
# -InDir may be the repo root when domino\ holds one NSF, or the NSF folder itself.
```

An export is a local tree under `domino/`. It is not committed. Folders follow Package Explorer. A path with directories keeps them: `mail/user.nsf` becomes `domino/mail/user.nsf-<replica id>/`. File names are `<design name>-<note id>.dxl`.

```
domino/<nsf path>-<replica id>/
  manifest.json                 # nsfPath, replicaId, type, path, name
  AppProperties/                # acl, application properties, xspdesign.properties
  Code/Agents/
  Code/Jars/
  Code/Java/                    # package path under here
  Code/ScriptLibraries/
  Code/WebServices/
  Code/WebServiceConsumer/
  Code/actions/
  Code/dbscript.lsdb-….dxl
  CompositeApplications/
  CustomControls/
  Folders/
  Forms/
  Framesets/
  Pages/
  Resources/                    # about, using
  Resources/Applets/
  Resources/DataConnections/
  Resources/Files/
  Resources/Images/             # includes $DBIcon
  Resources/StyleSheets/
  Resources/Themes/
  SharedElements/Columns/
  SharedElements/Fields/
  SharedElements/Navigators/
  SharedElements/Outlines/
  SharedElements/Subforms/
  Views/
  WebContent/                   # files outside WEB-INF
  WebContent/WEB-INF/           # faces-config, classes, xsp.properties
  XPages/
  .settings/
  .classpath-….dxl
  .project-….dxl
  build.properties-….dxl
  plugin.xml-….dxl
  _designnotes/                 # named notes with no Package Explorer folder
```

Package Explorer also shows JRE System Library, Plug-in Dependencies, Referenced Libraries, Data, and `META-INF`. Those are not design notes. `.classpath` and `plugin.xml` carry the JRE and plug-in references. Jars stored in the NSF are under `Code/Jars`. Designer creates `META-INF/MANIFEST.MF` when the XPages project is opened. Documents stay out of this export. A note with no name, such as the system view `$FormulaClass`, is left out.

`import-from-repo.ps1` reads `manifest.json` (`type` + path) and sends the DXL. The API removes `replicaid` before Domino reads it, so the target database keeps its own replica id.

After PUT, the API compiles LotusScript (script libraries first) and returns a `lotusscript` summary. Notes "Error processing use list module" after a restore usually means code was not compiled — re-import or run Designer **Recompile All LotusScript**. Some complex libraries (e.g. DDM) can still report syntax errors after DXL round-trip — open those in Designer if needed.

**Bulk-only type** (no list/get/put/delete endpoints; only in `/export` and `/import`): `designnote` for a remaining named design note. The export folder is `_designnotes`. XPages project files (`.classpath`, `.project`, `plugin.xml`, `build.properties`, `.settings/`, `WEB-INF/` except `xsp.properties`, i18n `*.properties`) are `xpagesresource` on `/xpagesresources`. Percent-encode a slash in the path (`WEB-INF%2Ffaces-config.xml`). Starters: `GET /defaults/{type}` for `xpagesresource` (not `designnote`).

**Blank NSF baseline** (reference DXL, not starters): [`resources/defaults/blank/`](src/main/resources/defaults/blank/) — `acl.dxl`, `applicationproperties.dxl`, `image-DBIcon.dxl`, `view-default.dxl`, plus `manifest.json` from a Domino blank database.

## Coverage (Domino Designer)

Sections follow Package Explorer. "In" = typed DXL CRUD; "Bulk" = `/export` and `/import` only.

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
| AppProperties | Application Properties | `/applicationproperties` (singleton; GET/PUT update-only, no create/DELETE — always present with NSF) |
| AppProperties | Database icon (`$DBIcon`) | `/dbicon` (singleton; GET/PUT update-only, no create/DELETE — always present with NSF; not under `/images`; export folder `Resources/Images`) |
| WebContent/WEB-INF | Xsp Properties | `/xspproperties` (singleton; GET/POST/PUT/DELETE — note name `WEB-INF/xsp.properties`) |
| Resources | Images | `/images` |
| Resources | Files | `/fileresources` |
| WebContent | Files outside `WEB-INF` | `/webcontent` (a name under `WEB-INF` is rejected; those notes stay `xpagesresource`) |
| Resources | Style Sheets | `/stylesheets` |
| Resources | Themes | `/themes` |
| Resources | About Document | `/about` (singleton) |
| Resources | Using Document | `/using` (singleton) |
| XPages | XPages | `/xpages` (named — list/create + `/{name}` get/update/delete; not a singleton). `POST /xpages` also ensures project skeleton. |
| Custom Controls | Custom Controls | `/customcontrols` (`.xsp` notes without XPage `K` flag; not Composite Components) |
| (XPages project) | `.classpath`, `.project`, `plugin.xml`, `build.properties`, `.settings/`, `WEB-INF/` except `xsp.properties` | `/xpagesresources` |
| Shared Elements | Navigators | `/navigators` |
| Code | Web Service Providers | `/webservices` + `GET /defaults/webservice` |
| Code | Web Service Consumers | `/webserviceconsumers` + `GET /defaults/webserviceconsumer` |
| Code | Java (class/interface/enum) | `/javasources` — kinds `class` \| `interface` \| `enum` |
| Code | Jars | `/jars` |
| Resources | Data Connections | `/dataconnections` (export folder `Resources/DataConnections`) |
| Resources | Applets | `/applets` |
| Composite Applications | Wiring Properties | `/wiringproperties` |
| Composite Applications | Applications | `/compositeapplications` |
| Composite Applications | Components | `/compositecomponents` |
| AppProperties | ACL | `/acl` (singleton; also in bulk; export file `AppProperties/acl.dxl`) |

Singletons: most use `GET` / `POST` (create) / `PUT` (update) / `DELETE` on the collection path. Exceptions: **ACL** (GET/PUT, no DELETE); **Application Properties** and **`$DBIcon`** (GET/PUT only — always present with the NSF, no create/DELETE).

### Not a design export

| What you see | What happens |
|--------------|----------------|
| Named note with no Package Explorer folder | Bulk type `designnote`, folder `_designnotes` |
| Note with no name (`$FormulaClass` and similar) | Omitted |
| Data (documents) | Omitted |
| JRE System Library, Plug-in Dependencies, Referenced Libraries, `META-INF` | Not stored as design notes. Pointers live in `.classpath` and `plugin.xml`. Designer writes `META-INF/MANIFEST.MF` when the project opens. |

### Access

On a database that already exists, the limit is that NSF's ACL.

| ACL on the target NSF | Operations |
|--|--|
| Designer | list, get, create, update, delete of design (not the ACL) |
| Manager | replace the ACL. A Manager includes the Designer rights above. |

The design import (`PUT /import`) into a database that does not exist yet checks the server document privilege **Create databases & templates**, then creates the file and imports the design. The same import into a database that already exists uses the ACL table above.

A Manager is treated as allowed to create shared and personal folders/views and LotusScript/Java agents, including private ones. Domino does not store those checkboxes on a Manager entry. Delete documents stays a stored flag at every level, including Manager. Designer and below still need the checkbox stored on the ACL entry. The API never deletes a database.

## Endpoints

Named types share the same CRUD shape: **list** `GET /{types}`, **create** `POST /{types}` (optional `?name=`; empty body + `?kind=&name=` for kinded types), **get/update/delete** on `/{types}/{name}`. WebContent, jars, applets, data connections, web service providers and consumers, wiring properties, and composite applications and components use that shape. Their tables are not repeated below. WebContent rejects a name under `WEB-INF`.

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

Always present with the NSF — update only. Not listed under `/images`. `PUT /import` that creates an NSF writes the embedded 64×64 Kyber icon unless the payload already has a `dbicon` (or an image named `$DBIcon`). Design refresh is allowed to replace it.

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

Named type (many per NSF) — same CRUD shape as forms/pages, not a singleton. Creating an XPage also creates the project files that are missing: `plugin.xml`, `.project`, `.classpath`, `build.properties`, `faces-config`, Activator, and `xsp.properties`.

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/xpages?nsfPath=` |
| POST | `/api/kyber-v1/xpages?nsfPath=` (`text/xml`; prefer Designer-exported DXL; optional `?name=`; empty body + `?name=` → starter; creates missing project files) |
| GET | `/api/kyber-v1/xpages/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/xpages/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/xpages/{name}?nsfPath=` |

### Custom Controls

XPages Custom Controls (Designer "Custom Controls") — `.xsp` design notes with `$Flags` like `gC~4;` (no `K`). Not the same as Composite Components (bulk type `compositecomponent`).

| Method | Path |
|--------|------|
| GET | `/api/kyber-v1/customcontrols?nsfPath=` |
| POST | `/api/kyber-v1/customcontrols?nsfPath=` (`text/xml`; Designer-exported DXL; optional `?name=`) |
| GET | `/api/kyber-v1/customcontrols/{name}?nsfPath=` |
| PUT | `/api/kyber-v1/customcontrols/{name}?nsfPath=` (`text/xml`; **404** if missing) |
| DELETE | `/api/kyber-v1/customcontrols/{name}?nsfPath=` |

## Install

The server install is in the [repository README](../README.md#install): copy the release JAR to `%DRAPI_HOME%\libs\infoware-kyber.jar`, remove older Kyber and legacy design JARs, then `tell restapi quit` and `load restapi`. `tell restapi reload` does not load a replaced JAR.

Build from this directory and install in one step:

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

**Output:** `target/infoware-kyber.jar`, copied to `%DRAPI_HOME%\libs\infoware-kyber.jar`. JDK 21 and Maven 3.9 or newer. The script reads `keep-core`, `keep-extension`, and `keep-jnx-ext` from `%DRAPI_HOME%`.

## Tests

Unit tests (JUnit 5, no Domino) live under `extension/src/test/java`. Covered: DXL helpers, kind adapters, classpath starters, alphabetical NSF pick helper, blank baseline resources, OpenAPI shape (incl. always-present GET/PUT-only).

```powershell
cd extension
.\build.ps1   # runs mvn package (includes tests)
# or:
mvn test
```

Live smoke against DRAPI. `tools/smoke-kyber-api.ps1` creates `kybertest.nsf` and runs list/create/get/put/delete for every typed CRUD collection (including agent, script-library and Java-source kinds), plus singleton create/update and get/put for application properties, the database icon and the ACL. Each created element is written again with `Hello World` and read back. LotusScript, formula and simple-action agents are then run, and each run writes `Hello World` onto a document. The LotusScript agent calls `SmokeLibLs`. Deleted elements are created again so the NSF stays populated. Domino URL commands then open the database, form, page, about document, using document, view, folder, navigator, frameset, icon, image, file, stylesheet, agents, web service and XPage. `?CreateDocument` saves a document that the view, `?OpenDocument` and `?EditDocument` show as `Hello World`, and `?DeleteDocument` removes it. The run exports `kybertest.nsf` and imports that tree into `kybersmoke-import.nsf`. The imported design keeps the target database's replica id:

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

1. OpenAPI paths + `operationId`s (`listX`, `getX`, `createX`, `updateX`, `deleteX`) under tag **`design-element`**; `/defaults*`, `/export`, and `/import` under **`design-handler`**
2. `dbrequests/design` handlers (JNX list/get/delete, DXL import via `DesignCrud` / `DesignDxl`)
3. `handlers/design/CreateX` and `UpdateX` extending `AbstractImportDxlHandler` for `text/xml` **POST** (create) and **PUT** (update)
4. Document the type in this README table

Do **not** use OpenAPI tag `design` — that maps HCL Setup's Design verticle package. The catalog version id must stay **`kyber`** (see `config.json`).