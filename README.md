# Kyber — Infoware REST API for design elements

Kyber is complete CRUD for the design elements in a Domino NSF. The design you send and store is [DXL](#dxl), the XML Domino Designer already documents. The elements you want to reuse live in [`kyber.nsf`](#templates-in-kybernsf) on the server, and an empty create copies one of them.

Kyber runs as a JAR inside HCL Domino REST API (KEEP / DRAPI). It is an Infoware product, not an HCL product, and it is published under [Apache-2.0](LICENSE).

| | |
|--|--|
| **Version** | 0.24.15 |
| **API** | `/api/kyber-v1` |
| **JAR** | `infoware-kyber.jar` |
| **Catalog** | OpenAPI version id `kyber` (the Admin UI card) |
| **Swagger tags** | `design-element` for typed CRUD, `design-handler` for `/defaults`, `/export`, `/import`, and `/databases` |
| **Template NSF** | `kyber.nsf` (`kyber.defaultNsf`) |
| **Reference** | [extension/README.md](extension/README.md) |
| **Source** | https://github.com/infoware-se/kyber |

## Contents

- [What you can do](#what-you-can-do)
- [Install](#install)
- [Quick start](#quick-start)
- [Access](#access)
- [Coverage](#coverage)
- [Round trip](#round-trip)
- [Security](#security)

## What you can do

### Every design element

Each design element has its own collection. A form is `/forms`, an agent is `/agents`, an XPage is `/xpages`. The same five calls cover views, folders, pages, framesets, shared elements, script libraries, Java, jars, applets, web services, resources, WebContent, themes, composite applications, and XPages project files. The full list is [Coverage](#coverage). Every call names the database with `?nsfPath=`.

| | |
|--|--|
| **List** | `GET /{collection}?nsfPath=` |
| **Create** | `POST /{collection}?nsfPath=&name=` |
| **Get** | `GET /{collection}/{name}?nsfPath=` |
| **Update** | `PUT /{collection}/{name}?nsfPath=` |
| **Delete** | `DELETE /{collection}/{name}?nsfPath=` |

A list returns the names, and an `elements` array with `fileName`, `aliases`, `kind`, and `comment` when the note has them. `aliases` is the part of the name after `|`, the name a URL or `OpenForm` uses. A get returns the DXL. When the note is one script or one text file, the get also returns that text as `code`.

The ACL, the application properties, and the database icon are get and put. Domino creates those three with the NSF. A named note with no collection of its own travels in the [round trip](#round-trip) as `designnote`. Documents are application data. A note with no name, such as `$FormulaClass`, is left out of an export.

### DXL

Create, update, and the round trip use Domino DXL, sent as `text/xml`. It is the same XML Designer exports and imports. The grammar is the DTD that ships with Designer, `xmlschemas/domino_*.dtd`.

`code` is the readable text inside a note that has one body: an agent, a script library, a Java source, a file resource, a style sheet, a WebContent file, or an XPages project file. Send it back as `text/plain` or `{"code":"..."}`. Kyber replaces that body and keeps the rest of the DXL. A form has many events, so its scripts stay in the DXL.

### Templates in kyber.nsf

`kyber.nsf` is the template library, an ordinary NSF on the server. Design the forms, agents, script libraries, and other elements you want to reuse, and store them there. The file name is `kyber.defaultNsf` in `kyber.properties`, and the default is `kyber.nsf`.

`GET /defaults` lists the template types. `GET /defaults/{type}` returns the first element of that type, in case-insensitive name order. Agents, script libraries, and Java sources take `?kind=` so the match is the right kind. An empty create copies that element into the target NSF and names it with `?name=`. For those three types the empty create is `?kind=&name=`. When `kyber.nsf` has no element of that type, Kyber uses the starter in the API. `source` is `nsf` or `api`.

Kyber creates `kyber.nsf` on the first design write when the file is missing. The new file has a title, an ACL, and About and Using text that describes the database. It holds no templates until you add them. While that About and Using text is still the text Kyber wrote, those two documents are usage text and are not templates. The database icon is the tile for `kyber.nsf` and is not a template either. Replace an element in `kyber.nsf` and the next empty create uses the replacement.

## Install

The Domino server stays up. Installing Kyber restarts the `restapi` task only.

### Install a release

1. Download `infoware-kyber-<version>.jar` from the [releases](https://github.com/infoware-se/kyber/releases). The version in the table above is the current one.
2. Copy it to `%DRAPI_HOME%\libs\infoware-kyber.jar`.
3. In that same folder, remove any older `infoware-kyber*.jar`, and any leftover `keep-design-*.jar` or `iws-design-*.jar`.
4. From the Domino console:

```text
tell restapi quit
load restapi
```

`tell restapi reload` leaves the old JAR loaded. Quit, then load.

### Build and install from source

Building needs JDK 21 and Maven 3.9 or newer. The script reads `keep-core`, `keep-extension`, and `keep-jnx-ext` from `%DRAPI_HOME%`.

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1 -InstallToDrapi
```

The script runs the tests, writes `extension\target\infoware-kyber.jar`, quits restapi, replaces the JAR in `%DRAPI_HOME%\libs\`, and loads restapi. `.\build.ps1` without `-InstallToDrapi` only builds.

## Quick start

Sign in. The token is the `bearer` field:

```powershell
$base = "https://your-domino-server:8880"
$auth = Invoke-RestMethod -Method POST -Uri "$base/api/v1/auth" `
    -ContentType "application/json" `
    -Body '{"username":"user","password":"secret"}'
$headers = @{ Authorization = "Bearer $($auth.bearer)" }
```

List the forms in a database that already exists:

```powershell
Invoke-RestMethod -Method GET `
    -Uri "$base/api/kyber-v1/forms?nsfPath=apps/mydb.nsf" `
    -Headers $headers
```

Create a form from the template in `kyber.nsf`. An empty body copies the template. `name` is the new element's name:

```powershell
Invoke-RestMethod -Method POST `
    -Uri "$base/api/kyber-v1/forms?nsfPath=apps/mydb.nsf&name=Contact" `
    -Headers $headers
```

Send your own design as DXL (`Content-Type: text/xml`). A full form is in the [manual example](extension/README.md#manual-test). A path such as `mail/user.nsf` is one `nsfPath` value. Percent-encode a slash inside an element name: `WEB-INF%2Ffaces-config.xml`.

Per-type create needs an existing NSF. The design import, `PUT /api/kyber-v1/import?nsfPath=&dbTitle=`, creates a missing NSF and imports the design. `POST /database` creates a missing NSF without importing design. `GET /databases` lists the NotesDbDirectory file type this user can open. `fileType` defaults to `database` (1247). `more` is set when other databases of that type exist, and those paths are not returned. The target keeps its own replica id.

A second create of the same name returns **409**. A missing element comes back as **404** or **400**, and the message says it was not found.

## Access

The call runs as the signed-in user. On a database that already exists, the limit is that NSF's ACL. Listing or reading a design element needs Designer, the same level as creating or changing one.

| ACL on the target NSF | Allowed |
|--|--|
| Designer | List, get, create, update, and delete design |
| Manager | Replace the ACL. A Manager includes the Designer rights above. |

The design import (`PUT /import`) into a database that does not exist yet checks the server document privilege **Create databases & templates**, then creates the file and imports the design. `POST /database` checks that same privilege and creates an empty NSF. The same import into a database that already exists uses the ACL table above. `GET /databases` needs Reader on the database that runs the call. `fileType` defaults to `database` (1247: NSF, NSG, or NSH). `template` (1248) is an NTF. `replica_candidate` (1245) is a database or template that is not disabled for replication. `template_candidate` (1246) is any database or template. A file is returned only when this user can open it. `more` is true when other databases of that type exist, and those paths are not returned.

A Manager may create shared and personal folders, views, and agents. Domino keeps those rights on the Manager level and does not store them as separate checkboxes. Delete documents stays a stored flag at every level. Designer and below need the design checkboxes stored on the ACL entry.

## Coverage

Collections are plural. A get, update, or delete adds `/{name}`.

| Designer | Elements | Collection |
|--|--|--|
| Forms, Views, Folders, Pages, Framesets | one collection each | `/forms` `/views` `/folders` `/pages` `/framesets` |
| Shared Elements | subforms, fields, columns, outlines, navigators | `/subforms` `/sharedfields` `/sharedcolumns` `/outlines` `/navigators` |
| Code | agents, shared actions, script libraries, Java, jars, web service providers and consumers | `/agents` `/sharedactions` `/scriptlibraries` `/javasources` `/jars` `/webservices` `/webserviceconsumers` |
| Code | database script | `/databasescript` |
| Resources | images, files, style sheets, themes, applets, data connections, About, Using | `/images` `/fileresources` `/stylesheets` `/themes` `/applets` `/dataconnections` `/about` `/using` |
| WebContent | files outside `WEB-INF` | `/webcontent` |
| XPages | XPages, custom controls | `/xpages` `/customcontrols` |
| XPages project | `.classpath`, `.project`, `plugin.xml`, `build.properties`, `.settings/`, `WEB-INF/` except `xsp.properties` | `/xpagesresources` |
| WebContent/WEB-INF | `xsp.properties` | `/xspproperties` |
| Composite Applications | wiring properties, applications, components | `/wiringproperties` `/compositeapplications` `/compositecomponents` |
| Application | properties, database icon (`$DBIcon`), ACL | `/applicationproperties` `/dbicon` `/acl` |

Three types take `?kind=` on an empty create:

| Type | `kind` |
|--|--|
| Agent | `lotusscript` `formula` `simpleaction` `java` `importedjava` |
| Script library | `lotusscript` `javascript` `ssjs` `java` |
| Java source | `class` `interface` `enum` |

About, Using, the database script, and Xsp Properties are one note per database: the calls sit on the collection path, with no `/{name}`. Application properties, the database icon, and the ACL are get and put on that same shape. `$DBIcon` is `/dbicon`. `GET /images` does not list it. A WebContent name under `WEB-INF` is rejected. Those notes are `/xpagesresources`, except `xsp.properties`, which is `/xspproperties`.

`POST /xpages` creates the XPages project files when they are missing: `plugin.xml`, `.project`, `.classpath`, `build.properties`, `faces-config`, Activator, and `xsp.properties`. Designer writes `META-INF/MANIFEST.MF` and `Local/plugin/Activator.java` when the project is opened. JRE System Library, Plug-in Dependencies, Referenced Libraries, and Data are views Designer builds. They are not design notes. `.classpath` and `plugin.xml` carry the JRE and plug-in references. Jars stored in the NSF are under `/jars`.

The blank-NSF baseline is the snapshot of what Domino puts in a new empty database: [`extension/src/main/resources/defaults/blank/`](extension/src/main/resources/defaults/blank/) holds `acl`, application properties, `$DBIcon`, the untitled default view, and `manifest.json`. A sync uses it so those notes are not treated as application design to remove. The files there are a reference. They are not the templates in `kyber.nsf`.

Endpoint tables for each collection are in [extension/README.md](extension/README.md).

## Round trip

The design export, `GET /api/kyber-v1/export?nsfPath=`, returns `{ nsfPath, elements: [{ type, name?, dxl }], skipped }`. The design import is `PUT /import` of that document. `POST /database` creates a missing NSF without importing design. Per-type create needs a database that already exists. On create, `dbTitle` sets the database title. The source `replicaid` is removed, so the target keeps its own. A new NSF receives the embedded Kyber database icon unless the payload already includes one, and a later design refresh is allowed to replace that icon. The ACL is also `GET`/`PUT /acl`, and replacing it requires Manager.

[`tools/export-to-repo.ps1`](tools/export-to-repo.ps1) writes that export to disk:

```text
domino/<nsf path>-<replica id>/
  manifest.json
  Forms/  Views/  Code/  Resources/  WebContent/  XPages/  …
```

The folders follow Package Explorer. Each note is `<name>-<note id>.dxl`. `manifest.json` is the import index (`type`, path, name). [`tools/import-from-repo.ps1`](tools/import-from-repo.ps1) reads it. `-OutDir` is a repository root, and the NSF folder is created under `domino/` there. A later export replaces that one NSF folder. These trees stay on disk. This repository does not commit them.

Named notes with no Designer folder land in `_designnotes` and are type `designnote`. That type is part of the bulk export. It has no list, get, or delete of its own.

## Security

A design call runs as the authenticated REST user and stops at that user's ACL on the target NSF. Report a vulnerability in private, not in a public issue: [open a security advisory](https://github.com/infoware-se/kyber/security/advisories/new). The policy is [SECURITY.md](SECURITY.md).

## License

[Apache-2.0](LICENSE).
