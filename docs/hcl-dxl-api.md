# HCL DXL API and Kyber

Domino REST API 1.1.8 adds an experimental [DXL Extension API](https://opensource.hcltechsw.com/Domino-rest-api/references/dxl/dxlAPIs.html) under `/api/dxl`. This note records how that API differs from Kyber 0.24.20. It was written on 21 October 2026 from HCL's published pages for that release.

HCL describes the API as something to try. It is not supported for production, and it stays off until a JSON file in `keepconfig.d` sets `versions.dxl.active` and the `restapi` task is restarted. The enable steps are in [Enable CalDAV, CardDAV, and DXL extension APIs](https://opensource.hcltechsw.com/Domino-rest-api/howto/production/davdxl.html). The release note is [What's new in 1.1.8](https://opensource.hcltechsw.com/Domino-rest-api/whatsnew/v1.1.8.html).

Both APIs send design as DXL, name the database with `nsfPath`, and use the Domino REST API bearer token. The Swagger document for HCL's API is `/api/dxl/schema/openapi.dxlext.json`.

The 1.1.8 change to `GET setup-v1/dxl`, which skips a design element it cannot export, belongs to the setup API. It is a different endpoint from `/api/dxl/export`.

## Where they differ

| Area | HCL `/api/dxl` | Kyber `/api/kyber-v1` |
|--|--|--|
| Status | Experimental, and off until `keepconfig.d` enables it | The regular design API |
| Read access | Reader lists, gets, exports, and validates | Designer lists and reads design, the same level as writing |
| Types | 16 classic types from `DbDesign` | Those types, plus XPages, Java, jars, web services, WebContent, style sheets, themes, shared actions, and singletons such as the database script. The list is [Coverage](../README.md#coverage) |
| One element | `PUT` creates or replaces. The body is an XML fragment. The response includes `importedCount` and the importer `log` | `POST` creates and `PUT` updates. A second create of the same name is **409**. A missing element is **404** or **400** |
| List | `name`, `unid`, and `noteId`. `GET /designsummary` groups every type and includes aliases | `name`, `noteId`, `unid`, `fileName`, `aliases`, and `kind`. `unid` stays when the name changes |
| Export | One `<database>` DXL. `full=true` includes properties and the ACL. `types=` filters. `include=properties` adds `propertiesDxl` | `{ nsfPath, elements: [{ type, name?, dxl }], skipped }`. Each note is its own DXL |
| Import | `POST /import` starts a job. Poll `GET /import/{jobId}`. Options are `designOption`, `documentsOption`, and `replaceDbProperties`. The finished job returns the importer log | `PUT /import` writes in the request. The source `replicaid` is removed, so the target keeps its own. The response has no log |
| Validate | `POST /validate` runs the importer as a dry run and returns `valid`, `errors`, and `warnings` | `POST /validate` checks the same document as `PUT /import` and does not write. The response is `valid` and `errors`, with no importer log. A note Domino itself cannot store still fails on the import |
| Signing | A written element, help document, and icon note are signed with the caller's ID from the ID vault, or with the server ID | A write is signed, except a compiled XPage whose DXL contains `$ClassData0`, and a Java library that has source and no `%%object%%.jar` |
| Icon | `/dbicon` is the icon note, NoteID `0xFFFF0010`. It also holds the launch settings | `/dbicon` is the `$DBIcon` image resource. A put also updates the workspace icon note and keeps that note's launch settings |
| Database | No category gate, and no create or delete of an NSF | `KyberEnabled` is required. `POST /database` creates an NSF. `DELETE /database` removes one when the caller is Manager |

## Types

HCL's `{type}` path is one of these. Each row is the `DbDesign` method HCL names for that path.

| Type | DbDesign |
|--|--|
| `form` | `getForm` |
| `subform` | `getSubform` |
| `view` | `getView` |
| `folder` | `getFolder` |
| `frameset` | `getFrameset` |
| `page` | `getPage` |
| `agent` | `getAgent` |
| `scriptlibrary` | `getScriptLibrary` |
| `sharedfield` | `getSharedField` |
| `sharedcolumn` | `getSharedColumn` |
| `outline` | `getOutline` |
| `navigator` | `getNavigator` |
| `imageresource` | `getImageResource` |
| `fileresource` | `getFileResource` |

A get of one element returns JSON: `type`, `name`, `unid`, `noteId`, and `dxl`. The `dxl` value is the element fragment, with no XML declaration and no DOCTYPE. A put sends that fragment as `text/xml`. HCL wraps it in `<database>` before the importer runs, so a declaration in the body is a parse error. The response is `status`, `importedCount`, and `log`. A delete returns `deleted`, `type`, `name`, and `unid`, and **404** when the element is missing.

`GET /designsummary` returns every present type, with `name`, `aliases`, `unid`, and `noteId`, and no DXL. A type with nothing in it is left out. `GET /{type}` returns `name`, `unid`, and `noteId` for that type.

## Export and import

`GET /export` returns one `<database>` document.

| Query | Result |
|--|--|
| `full=true` | Properties, ACL, and the design elements. `mode` is `full` |
| `types=form,view` | Only those types. `mode` is `filtered`, with `elementCount` |
| `include=properties` | Adds `propertiesDxl`, a separate DXL document of the database properties |

`POST /import` accepts a `<database>` document and returns `jobId` with `status` `running`. `GET /import/{jobId}` stays `running` until it is `complete` or `error`. An unknown or expired job is **404**. HCL strips a leading UTF-8 BOM and any whitespace before the first `<`.

| Option | Default | Values HCL documents |
|--|--|--|
| `designOption` | `replaceElseCreate` | `replaceElseCreate`, `replaceElseIgnore`, `ignoreElseCreate`, `create`, `ignore` |
| `documentsOption` | `ignore` | How documents in the DXL are handled |
| `replaceDbProperties` | `false` | Whether database properties are updated |

`POST /validate` checks that the XML is well formed and then runs the importer without writing. The DXL has to be wrapped in `<database>`. Reader is enough. A failed check returns `valid: false` and the errors, including when the root element is not `<database>`.

Kyber's export is the document described under [Round trip](../README.md#round-trip). [`tools/export-to-repo.ps1`](../tools/export-to-repo.ps1) writes one DXL file per note, in Package Explorer folders. The import reports each element. It has no `documentsOption` and no importer log in the response. `POST /validate` checks that document without running the importer.

## Access

| Call | HCL ACL |
|--|--|
| List, get, export, validate, and polling an import job | Reader |
| Create or replace an element, import, frameset layout, outline entries, About, Using, and the icon note | Designer |
| `PUT /database`, and every ACL call | Manager |

On Kyber, Designer lists, reads, creates, updates, and deletes design. Manager replaces the ACL and deletes an NSF whose category is `KyberEnabled`.

## Icon, About, and Using

HCL's `/dbicon` reads and writes the icon note, `DocumentClass.ICON`, NoteID `0xFFFF0010`. That note holds the workspace bitmap and the launch settings. HCL says it is separate from the `$DBIcon` image resource, and that both have to be present for the icon to look the same in the clients HCL names. Replacing the icon snapshots the launch settings and restores them afterward. The response field `propsRestored` says whether that restore happened. Deleting the note returns the database to the default workspace icon.

Kyber's `/dbicon` is the `$DBIcon` image resource. `GET /images` does not list it.

About is NoteID `0xFFFF0002` and Using is NoteID `0xFFFF0100`. HCL serves them at `/helpdoc/about` and `/helpdoc/using`, as DXL, and a delete removes the note. Kyber serves the same two notes at `/about` and `/using`.

`GET /database` returns JSON: `replicaId`, `title`, `filePath`, `designTemplate`, and `launchSettings`. The launch settings are read from the icon note. `PUT /database` changes the title and the launch settings that the body includes, and leaves the other fields as they are. That call needs Manager. Kyber's application properties are DXL on `/applicationproperties`.

## JSON beside DXL

HCL can change four notes without the caller sending DXL.

| Path | What the JSON is |
|--|--|
| `GET` and `PUT /frameset/{name}/layout` | Rows or columns, a size per frame, and a target of `page`, `view`, `form`, `frameset`, or `url` |
| `GET` and `PUT /outline/{name}/entries` | A tree of `label`, `target`, and `children`. A put replaces the outline |
| `GET` and `PUT /database` | Title and launch settings |
| `GET /acl`, `PUT /acl/entry/{name}`, `DELETE /acl/entry/{name}` | One ACL entry, with `level`, `type`, `roles`, and `flags` |

A role on an ACL entry has to exist already, or the put is **400**. `-Default-` and `LocalDomainServers` cannot be deleted. Every ACL call needs Manager.

Kyber replaces the ACL as one DXL document on `PUT /acl`, and that call needs Manager. The same two entries must remain. A payload that omits either of them is **400**, and the ACL is left unchanged. Kyber's `/dbicon` remains the `$DBIcon` image resource, and a put of it also updates the workspace icon note.

## What each API keeps to itself

HCL's DXL API has an import job with the importer's options, and the importer log in the response. Its dry run runs the importer. It also has the JSON for a frameset, an outline, launch settings, and a single ACL entry.

Kyber has the design outside those 16 types, including XPages, Java, jars, web services, and WebContent. It has the `KyberEnabled` category, `POST /database`, and `DELETE /database`. Its export is one DXL per note, with `skipped` for what was left out. The `comment` attribute in the DXL is kept. LotusScript is compiled on write. The response leaves out the importer log, `signedBy`, `code`, and `compiled`.
