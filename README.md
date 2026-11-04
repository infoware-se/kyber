# Kyber — Infoware REST API for HCL Domino design elements

[![CI](https://github.com/infoware-se/kyber/actions/workflows/ci.yml/badge.svg)](https://github.com/infoware-se/kyber/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/infoware-se/kyber)](https://github.com/infoware-se/kyber/releases/latest)
[![License](https://img.shields.io/github/license/infoware-se/kyber)](LICENSE)

Kyber is complete CRUD for the design elements in a Domino NSF. The design you send and store is [DXL](#dxl), the XML Domino Designer already documents. The elements you want to reuse live in [`kyber.nsf`](#templates-in-kybernsf) on the server, and an empty create copies one of them.

Kyber runs as a JAR inside HCL Domino REST API (KEEP / DRAPI). It is an Infoware Solutions Svenska AB product, not an HCL product, and it is published under [Apache-2.0](LICENSE).

| | |
|--|--|
| **Version** | 0.26.11 |
| **API** | `/api/kyber-v1` |
| **JAR** | `infoware-kyber.jar` |
| **Catalog** | OpenAPI version id `kyber` (the Admin UI card) |
| **Swagger tags** | `design-element` for typed CRUD and helpers (`/lookup`, `/fields`, `/columns`, `/summary`, `/diff`, `/schedules`, `/copy`, …), `design-handler` for `/export`, `/import`, `/database`, `/databases`, `/fromTemplate` |
| **Template NSF** | `kyber.nsf` (`kyber.defaultNsf`) |
| **Category** | `KyberEnabled`. A database without it responds 403 **Not Kyber enabled** |
| **Reference** | [extension/README.md](extension/README.md) |
| **HCL DXL API** | [How 1.1.8 differs from Kyber](docs/hcl-dxl-api-and-kyber.md) |
| **DXL round-trip fixes** | [Catalog + how to add fixes](docs/dxl-roundtrip-fixes.md) |
| **Source** | https://github.com/infoware-se/kyber |
| **MCP** | [Aldhani](https://github.com/infoware-se/aldhani) |
| **Deploy** | [Yavin](https://github.com/infoware-se/yavin) |
| **Contributing** | [CONTRIBUTING.md](CONTRIBUTING.md) |
| **Security** | [SECURITY.md](SECURITY.md) |

## Language

Comments, commit messages, and documentation are written in English.

## Kyber enabled

Every call acts only on a database whose catalog category is **KyberEnabled**. In Designer that field is Database properties, on the Design tab, Categories. Setting it on a database that already exists needs **Designer** or higher in that database ACL.

A database without that category responds **403** with the message **Not Kyber enabled**. `GET /databases` leaves it out.

`POST /database`, `POST /database/fromTemplate`, and a design import that creates a missing NSF, set the category on the new file. Those calls need the server privilege **Create databases & templates**. A blank field allows everyone. A name in the field may be a user, a server, or a group. Kyber gives the caller Manager on the new file and writes the category in the same step.

`DELETE /database` removes an NSF whose category is **KyberEnabled**. That call needs **Manager** on the file. A database without the category responds **403** **Not Kyber enabled**. `KeepConfig.nsf` cannot be deleted.

## Contents

- [Language](#language)
- [Kyber enabled](#kyber-enabled)
- [What you can do](#what-you-can-do)
- [Install](#install)
- [Quick start](#quick-start)
- [Access](#access)
- [Coverage](#coverage)
- [Round trip](#round-trip)
- [HCL DXL API](#hcl-dxl-api)
- [Improvements](#improvements)
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

A list returns the names, and an `elements` array with `noteId` and `unid`. `unid` stays when the name changes. `noteId` is local to this NSF and changes when the note is replaced. The array adds `fileName`, `aliases` and `kind` when the note has them. `aliases` is the part of the name after `|`, the name a URL or `OpenForm` uses. A get returns the DXL, with the same `noteId` and `unid`.

The ACL, the application properties, and the database icon are get and put. Domino creates those three with the NSF. A named note with no collection of its own travels in the [round trip](#round-trip) as `designnote`. Documents are application data. A note with no name, such as `$FormulaClass`, is left out of an export.

### DXL

Create, update, and the round trip use Domino DXL. Send it as `text/xml`, or as `application/json` with `dxl` set to that DXL. The design comment is the `comment` attribute in the DXL. A payload without that attribute leaves the comment already on the note.

### Templates in kyber.nsf

`kyber.nsf` is the template library, an ordinary NSF on the server. Design the forms, agents, script libraries, and other elements you want to reuse, and store them there. The file name is `kyber.defaultNsf` in `kyber.properties`, and the default is `kyber.nsf`.

An empty create copies an element of that type from `kyber.nsf` and names it with `?name=`. Optional `?template=` selects which template by case-insensitive name; without it, Kyber uses the first match in case-insensitive name order. Agents, script libraries, and Java sources take `?kind=` so the copy is the right kind. For those three types the empty create is `?kind=&name=` (and optional `?template=`). A `?template=` that is missing from `kyber.nsf` is **400**. When `kyber.nsf` has no element of that type and `template` is omitted, Kyber uses the starter DXL embedded in the JAR (`extension/src/main/resources/defaults`).

Kyber creates `kyber.nsf` on the first design write when the file is missing. The new file has a title, an ACL, and About and Using text that describes the database. It holds no templates until you add them. While that About and Using text is still the text Kyber wrote, those two documents are usage text and are not templates. The database icon is the tile for `kyber.nsf` and is not a template either. Replace an element in `kyber.nsf` and the next empty create uses the replacement.

**JAR starters (dev only).** Runtime never opens `kyber_jar.nsf`. That NSF holds one starter note per top-level `defaults/*.dxl` (not `blank/`, not tied to `kyber_blank.nsf`). Edit there in Designer, then sync into the JAR and rebuild:

```powershell
.\tools\kyber-jar-nsf.ps1 -Seed -Recreate -SkipCertificateCheck   # load JAR starters into kyber_jar.nsf
.\tools\kyber-jar-nsf.ps1 -Sync -SkipCertificateCheck             # write those notes back into defaults/*.dxl
```

**Empty-create reference (dev only, finished baseline).** `kyber_blank.nsf` shows what `POST /database` yields (chrome from `defaults/blank/*_default.dxl`). It is separate from JAR starters:

```powershell
.\tools\kyber-blank-nsf.ps1 -SkipCertificateCheck
.\tools\kyber-blank-nsf.ps1 -Recreate -SkipCertificateCheck
```

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

A second create of the same name returns **409**. A missing element throws `KeepExceptionNotFound` (OpenAPI **404**). KEEP often maps that exception to HTTP **400**; the message still says it was not found.

## Known limits

These are host/platform boundaries Kyber cannot remove:

| Limit | What clients should do |
|--|--|
| **DXL = Designer DTD** | Round-trip only what `xmlschemas/domino_*.dtd` defines. Gaps that Domino still stores get a targeted fix in [`DxlImportFixes`](docs/dxl-roundtrip-fixes.md) — not a second DXL dialect. |
| **Missing note → HTTP 400** | Kyber raises `KeepExceptionNotFound` (spec **404**). KEEP often maps that exception to **400** with a “not found” message. Treat **404** or **400 + not-found text** as missing. Live smoke accepts both. |

## Access

The call runs as the signed-in user. On a database that already exists, the limit is that NSF's ACL. Listing or reading a design element needs Designer, the same level as creating or changing one.

| ACL on the target NSF | Allowed |
|--|--|
| Designer | List, get, create, update, and delete design |
| Manager | Replace the ACL, and delete the NSF when its category is KyberEnabled. `-Default-` and `LocalDomainServers` must remain in the new ACL. A Manager includes the Designer rights above. |

The design import (`PUT /import`) into a database that does not exist yet checks the server document privilege **Create databases & templates**. A blank field allows everyone, and a name in the field may be a user, a server, or a group. The import then creates the file, sets the category **KyberEnabled**, and imports the design. `POST /database` and `POST /database/fromTemplate` check that same privilege and create an NSF (empty, or from a template). `DELETE /database` needs Manager on an NSF whose category is KyberEnabled. `KeepConfig.nsf` cannot be deleted. The same import into a database that already exists uses the ACL table above. `GET /databases` needs Reader on the database that runs the call. `fileType` defaults to `database` (1247: NSF, NSG, or NSH). `template` (1248) is an NTF. `replica_candidate` (1245) is a database or template that is not disabled for replication. `template_candidate` (1246) is any database or template. A file is returned only when this user can open it. `more` is true when other databases of that type exist, and those paths are not returned.

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
| Residual | named notes with no Designer folder | `/designnotes` (bulk folder `_designnotes`) |

Three types take `?kind=` on an empty create:

| Type | `kind` |
|--|--|
| Agent | `lotusscript` `formula` `simpleaction` `java` `importedjava` |
| Script library | `lotusscript` `javascript` `ssjs` `java` |
| Java source | `class` `interface` `enum` |

About, Using, the database script, and Xsp Properties are one note per database: the calls sit on the collection path, with no `/{name}`. Application properties, the database icon, and the ACL are get and put on that same shape. `$DBIcon` is `/dbicon`. A put of that image also updates the workspace icon note, and the launch settings on that note stay. `GET /images` does not list it. A WebContent name under `WEB-INF` is rejected. Those notes are `/xpagesresources`, except `xsp.properties`, which is `/xspproperties`.

`POST /xpages`, and creating a Java agent, imported-Java agent, Java script library, Java source, or XPage, runs `XPagesProject.ensure`: create-if-absent for missing project notes (`plugin.xml`, `META-INF/MANIFEST.MF` with `Bundle-SymbolicName: kyber_nsf`, `.project`, `Local/plugin/Activator.java`, `.classpath`, `build.properties`, `faces-config`, compiled Activator when importable, `xsp.properties`), then heal/dedupe when Designer pollution is present. The stored `.classpath` lists JRE + plug-in containers only — not Designer's temp `Local/` source folder. Stored `plugin.xml` omits `class=plugin.Activator` (that attribute forces a missing Local build path). The create JSON may include `xpagesProject` with `created`, `skipped`, `failed`, and `repaired` (for example `.classpath` after a Local/ heal). JRE System Library, Plug-in Dependencies, Referenced Libraries, and Data are views Designer builds — not design notes. Jars stored in the NSF are under `/jars`.

The blank-NSF baseline under [`extension/src/main/resources/defaults/blank/`](extension/src/main/resources/defaults/blank/) holds the DXL empty create uses (`All Documents` default view, outline, pages, framesets, launch) plus reference ACL/props/icon files and `manifest.json`. A sync uses the baseline so those notes are not treated as application design to remove. They are not the templates in `kyber.nsf`.

Endpoint tables for each collection are in [extension/README.md](extension/README.md).

## Round trip

The design export, `GET /api/kyber-v1/export?nsfPath=`, returns `{ nsfPath, elements: [{ type, name?, noteId?, unid?, dxl }], skipped }`. Each `skipped` row always has `type` and `message`; `name` when known. That shape is stable for clients. The design import is `PUT /import` of that document. `POST /validate` checks the same document and does not write. `valid` is false when an element would be rejected before Domino stores it. Each error has `index`, `type`, `message`, and a stable `code` (for example `invalid_dxl`, `protected_acl`). The response has no importer log. A note Domino itself cannot store still fails on the import. `POST /database` creates a missing NSF without importing design. `PUT /database?dbTitle=` sets the title on an existing NSF without rewriting application-properties DXL. Per-type create needs a database that already exists. On create, `dbTitle` sets the database title. The source `replicaid` is removed, so the target keeps its own. A new NSF receives the embedded Kyber database icon unless the payload already includes one, and a later design refresh is allowed to replace that icon. The ACL is `GET`/`PUT /acl`. GET returns DXL plus structured `entries` and `roles`. Replacing the ACL requires Manager. `-Default-` and `LocalDomainServers` must remain. A payload that omits either of them is **400**, and the ACL is left unchanged.

The DXL DTD that ships with Designer, `xmlschemas/domino_14_5_1.dtd`, is the limit of a round trip. A design detail that the DTD does not define is left unhandled by the exporter and the importer.

**DXL round-trip fixes** live in [`DxlImportFixes`](extension/src/main/java/se/infoware/domino/kyber/dbrequests/design/DxlImportFixes.java) (after every design import / prepare-incoming hooks) and the catalog [`docs/dxl-roundtrip-fixes.md`](docs/dxl-roundtrip-fixes.md) — framesets, subform tablerows, view `$Readers`, unmatched `REM {DNT Begin}`, ACL protect entries, and related in-import cleanups. Live coverage: [`tools/smoke-dxl-roundtrip.ps1`](tools/smoke-dxl-roundtrip.ps1) and full-server NTF regress [`tools/run-bulk-roundtrip-advanced.ps1`](tools/run-bulk-roundtrip-advanced.ps1). Other round-trip limits still follow the Designer DTD; when a new gap appears, add a fix to `DxlImportFixes` and the catalog — do not depend on optional server template NSFs.

[`tools/export-to-repo.ps1`](tools/export-to-repo.ps1) writes that export to disk:

```text
domino/<nsf path>-<replica id>/
  manifest.json
  Forms/  Views/  Code/  Resources/  WebContent/  XPages/  …
```

The folders follow Package Explorer. Each note is `<name>-<note id>.dxl`. `manifest.json` is the import index (`type`, path, name). [`tools/import-from-repo.ps1`](tools/import-from-repo.ps1) reads it. `-OutDir` is a repository root, and the NSF folder is created under `domino/` there. A later export replaces that one NSF folder. These trees stay on disk. This repository does not commit them.

Named notes with no Designer folder are type `designnote`: typed CRUD on `/designnotes`, and the same notes in the bulk export under `_designnotes`.

## HCL DXL API

Domino REST API 1.1.8 adds an experimental DXL API under `/api/dxl`. Kyber does not call it. Kyber stays a KEEP extension under `/api/kyber-v1`. HCL's API is off until `keepconfig.d` enables it, and HCL does not support it for production. It covers 16 classic design types and returns one `<database>` DXL. The differences from Kyber, including access, import, and the icon note, are in [docs/hcl-dxl-api-and-kyber.md](docs/hcl-dxl-api-and-kyber.md).

## Boundary with Aldhani and Yavin

Kyber is one of three Infoware Domino products. The detailed family map lives in [Yavin](https://github.com/infoware-se/yavin); this table is the short form for contributors here.

| | Kyber | Aldhani | Yavin |
|--|--|--|--|
| Role | Design API on DRAPI | MCP server for agents | Deploy / ops |
| Speaks | `/api/kyber-v1` | MCP `POST /mcp` on Domino HTTP | Domino console / Notes COM / file copy |
| Mutates NSF design | Yes (CRUD, export/import, validate, compile, content, inherit, …) | No — only by calling Kyber as `kyberUser` | No |
| NSF gate | Catalog category `KyberEnabled` + Domino ACL | `allowedNsf` (and optional `writeNsf`) before Kyber | Host reachable; no NSF category logic |
| Surface | Full design API | Subset of tools + allowlist + `allowWrite` | Host profiles, version pins, quit/load |
| Policy / refusals | None for capable Manager clients | May refuse database delete, full import, ACL replace | N/A (does not call design APIs) |

**Where new work goes**

| Kind of change | Belongs in |
|--|--|
| Design route, DXL round-trip, blank NSF chrome, JAR element defaults | **Kyber** (this repo) |
| MCP tool, allowlist / `writeNsf`, `allowWrite`, refuse policy, agent UX | **[Aldhani](https://github.com/infoware-se/aldhani)** |
| Host inventory, version pins, promote lab→prod, restart restapi/http, post-deploy “what’s loaded?” smoke | **[Yavin](https://github.com/infoware-se/yavin)** |

Kyber’s pre-release API smoke (`tools/smoke-kyber-api.ps1`) is not Yavin’s post-deploy smoke. See also [Aldhani’s Boundary with Kyber](https://github.com/infoware-se/aldhani#boundary-with-kyber).

## Improvements

Kyber is the design API on DRAPI (Domino JNX). Ship complete design routes here; [Aldhani](https://github.com/infoware-se/aldhani) wraps a subset behind MCP policy, and [Yavin](https://github.com/infoware-se/yavin) deploys which build runs on a host. A local wishlist for **DRAPI and Domino JNX** (API and docs) stays under `ignore/` and is not published with the repo.

**Shipped in 0.26.11**

1. `GET /schedules` — NSF-wide agent trigger/schedule inventory (`trigger=`, `scheduledOnly=` filters); no full DXL.

**Shipped in 0.26.10**

1. `GET|POST /diff` — compare two live notes (`type`/`name`/`toName`) or a note to proposed DXL; structured field/column/comment ops plus DXL line hunks (volatile attrs stripped).

**Shipped in 0.26.9**

1. `GET /summary?type=&name=` (or `unid` / `noteId`) — aliases, kind, comment, agent trigger/schedule, field/column counts, view forms; DXL omitted unless `includeDxl=true`.

**Shipped in 0.26.8**

1. `GET /fields?type=form|subform&name=` — field name/type/kind inventory without full DXL.
2. `GET /columns?type=view|folder&name=` — column title/formula/flags inventory without full DXL.

**Shipped in 0.26.7**

1. Family boundary docs: Kyber | Aldhani | Yavin (where new work goes; smoke vs post-deploy).
2. `tools/check-version-sync.ps1` + `tools/release-gate.ps1` (unit/package + Extended API smoke; Kyber-only).
3. CI optional self-hosted `unit` when `KYBER_SELF_HOSTED_UNIT=true`.

**Shipped in 0.26.6**

1. CI `verify` checks pom version against root README, extension README, and OpenAPI `info.version`.
2. README **Known limits** (Designer DTD round-trip; KEEP maps missing notes to HTTP 400); OpenAPI `404NotFound` spells out the KEEP mapping.

**Shipped in 0.26.5**

1. Track `defaults/xpages-project/activator-java.dxl` so clean clones and release JARs include the `Local/plugin/Activator.java` starter.

**Shipped in 0.26.4**

1. Docs and OpenAPI document `XPagesProject.ensure` (heal/dedupe, no Local/`Activator` class) and create-response `xpagesProject` (`created` / `skipped` / `failed` / `repaired`).
2. Extension README: Logging (`KyberLog`) and Extended smoke heal probe.

**Shipped in 0.26.3**

1. XPages skeleton `.classpath` omits Designer’s temp `Local/` source folder; `plugin.xml` omits `class=plugin.Activator` (avoids missing Local build-path errors).
2. `XPagesProject.ensure` dedupes and heals polluted `.classpath` / `plugin.xml`; create responses include `xpagesProject.repaired`.
3. Shared `KyberLog` (JUL under `se.infoware.domino.kyber`) for create/update/delete/import/ensure/compile.
4. Test layers: `tools/run-unit-tests.ps1`, must-pass smoke, `-Extended` heal probe.

**Shipped in 0.26.2**

1. Blank empty-create chrome uses `defaults/blank/*_default.dxl` (`All Documents` view, 56px title row, titled **Kyber Blank**).
2. Domino’s empty-`$TITLE` view stub is removed after create so Designer does not show a duplicate untitled view.
3. `kyber_jar.nsf` is starters-only (no blank chrome); `-Seed`/`-Sync` rewrite top-level `defaults/*.dxl` for JAR defaults.
4. `xpagesresource` starter is a valid empty `local/…` note (importable); Eclipse `.project` stays under `xpages-project/`.

**Shipped in 0.26.1**

1. Missing design notes return **404** (`KeepExceptionNotFound`); failed post-write lookup is a server error.
2. `PUT /acl` refuses dropping `-Default-` / `LocalDomainServers` before template ACL inject.
3. Empty `PUT /import` on an existing NSF skips DxlImporter/`compileAll` (avoids nrestapi LockMemHandle panics on this host).
4. `/content` reads Designer `<png>`/`<gif>` bodies; agent kind detects `agenttype=formula|simpleaction`.
5. Blank NSF web launch opens `$MainFrameset`; restart/smoke wait for a stable restapi window after reload.

**Shipped in 0.26.0**

1. Lookup and search — `GET /lookup?unid=`|`noteId=`, `GET /search?q=`, agent list `trigger` metadata.
2. Copy, rename, patch — `POST /copy`, `POST /rename`, `PATCH /patch` (`comment` / `alias`).
3. Binary resources — `GET|PUT /content?type=image|fileresource|jar|dbicon` with `contentBase64`.
4. Compile and category — `POST /compile`, `PUT /database/category?enabled=`, import response `compile` + truncated `importerLog`.
5. Templates — `GET /templates?type=`, `POST /defaults/preview` (empty create `?template=` already existed).
6. `designnote` CRUD — `/designnotes` collection.
7. From template / inherit — `POST /database/fromTemplate`, `POST /database/inherit` (`Database.refreshDesign`).

Also shipped earlier. Bulk DXL round-trip + typed CRUD (applet/dataconnection, alias-collision fix) in **0.25.0**. `PUT /database?dbTitle=`, structured `GET /acl`. Special Domino titles: **`/named/{type}?name=`**. ACL **replace** and database **delete** stay privileged Manager operations; MCP products may refuse them.

**Next (high value for agents / Aldhani)**

Aldhani wraps Kyber; it will not parse DXL locally for these. Ship the routes here first. **Fields** / **Columns** (0.26.8), **Summary** (0.26.9), **Diff** (0.26.10), and **Schedules** (0.26.11) shipped.

**Next (medium)**

1. One-element create-or-replace import (not full `PUT /import`) when a client has a single DXL note.

Out of scope for Kyber. Application documents, DQL, running agents as a general automation API, server console, admin directory changes, Aldhani MCP policy, and Yavin host deploy / post-deploy smoke.


## Security

A design call runs as the authenticated REST user and stops at that user's ACL on the target NSF. Report a vulnerability in private, not in a public issue: [open a security advisory](https://github.com/infoware-se/kyber/security/advisories/new). The policy is [SECURITY.md](SECURITY.md).

## License

Kyber is open-source software licensed under the Apache License, Version 2.0. Copyright 2026 Infoware Solutions Svenska AB.

[Apache-2.0](LICENSE).
