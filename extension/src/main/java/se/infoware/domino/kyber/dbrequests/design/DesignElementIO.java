package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.AboutDocument;
import com.hcl.domino.design.DatabaseScriptLibrary;
import com.hcl.domino.design.DbProperties;
import com.hcl.domino.design.DesignAgent;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.ScriptLibrary;
import com.hcl.domino.design.SharedActions;
import com.hcl.domino.design.UsingDocument;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Export / import all supported design elements as CRUD-identical DXL (same as Get-star /
 * Create-star), plus bulk-only types (navigators, jars, …) that have no CRUD endpoints.
 */
final class DesignElementIO {

  private DesignElementIO() {}

  static JsonArray exportAll(Database db) throws Exception {
    return exportAll(db, null);
  }

  /**
   * @param skipped when non-null, receives {@code {type, name?, message}} for notes that were not
   *     written to the returned array. A typed export failure is omitted here when the same name
   *     is later exported (for example as {@code designnote}).
   */
  static JsonArray exportAll(Database db, JsonArray skipped) throws Exception {
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    JsonArray elements = new JsonArray();
    Set<String> exportedUnids = new HashSet<>();

    exportSingleton(
        elements,
        "acl",
        () -> {
          StringWriter sw = new StringWriter();
          exporter.exportACL(db, sw);
          String dxl = sw.toString();
          if (dxl == null || dxl.isBlank()) {
            return Optional.empty();
          }
          return Optional.of(dxl);
        });

    exportSingleton(
        elements,
        "applicationproperties",
        () -> {
          DbProperties props = db.getDesign().getDatabaseProperties();
          if (props == null || props.getDocument() == null) {
            return Optional.empty();
          }
          trackUnid(exportedUnids, props.getDocument());
          return Optional.of(exporter.exportDocument(props.getDocument()));
        });

    exportSingleton(
        elements,
        "about",
        () -> {
          Optional<AboutDocument> opt = db.getDesign().getAboutDocument();
          if (opt.isEmpty()) {
            return Optional.empty();
          }
          trackUnid(exportedUnids, opt.get().getDocument());
          return Optional.of(exporter.exportDocument(opt.get().getDocument()));
        });

    exportSingleton(
        elements,
        "using",
        () -> {
          Optional<UsingDocument> opt = db.getDesign().getUsingDocument();
          if (opt.isEmpty()) {
            return Optional.empty();
          }
          trackUnid(exportedUnids, opt.get().getDocument());
          return Optional.of(exporter.exportDocument(opt.get().getDocument()));
        });

    exportSharedActions(elements, db, exporter, exportedUnids);

    exportSingleton(
        elements,
        "databasescript",
        () -> {
          Optional<DatabaseScriptLibrary> opt = db.getDesign().getDatabaseScriptLibrary();
          if (opt.isEmpty()) {
            return Optional.empty();
          }
          trackUnid(exportedUnids, opt.get().getDocument());
          return Optional.of(exporter.exportDocument(opt.get().getDocument()));
        });

    exportNamed(elements, "image", db.getDesign().getImageResources(), exporter, exportedUnids);
    exportNamed(elements, "fileresource", db.getDesign().getFileResources(), exporter, exportedUnids);
    exportNamed(elements, "stylesheet", db.getDesign().getStyleSheets(), exporter, exportedUnids);
    exportNamed(elements, "theme", db.getDesign().getThemes(), exporter, exportedUnids);
    exportScriptLibraries(elements, db, exporter, exportedUnids);
    exportNamed(elements, "sharedfield", db.getDesign().getSharedFields(), exporter, exportedUnids);
    exportNamed(elements, "sharedcolumn", db.getDesign().getSharedColumns(), exporter, exportedUnids);
    exportNamed(elements, "subform", db.getDesign().getSubforms(), exporter, exportedUnids);
    exportNamed(elements, "form", db.getDesign().getForms(), exporter, exportedUnids);
    exportNamed(elements, "page", db.getDesign().getPages(), exporter, exportedUnids);
    exportNamed(elements, "outline", db.getDesign().getOutlines(), exporter, exportedUnids);
    exportNamedSafe(
        elements, "navigator", db.getDesign().getNavigators(), exporter, exportedUnids, skipped);
    // JNX FramesetImpl can throw on missing frameset version (Optional.empty); skip bad
    // notes so bulk GET continues — residual notes may still appear as designnote.
    exportNamedSafe(
        elements, "frameset", db.getDesign().getFramesets(), exporter, exportedUnids, skipped);
    exportNamed(elements, "view", db.getDesign().getViews(), exporter, exportedUnids);
    exportNamed(elements, "folder", db.getDesign().getFolders(), exporter, exportedUnids);
    exportNamed(elements, "agent", db.getDesign().getAgents(), exporter, exportedUnids);
    exportCustomControls(elements, db, exporter, exportedUnids);
    exportWebServices(elements, db, exporter, exportedUnids);
    exportNamed(elements, "xpage", db.getDesign().getXPages(), exporter, exportedUnids);
    exportNamedSafe(
        elements,
        "wiringproperties",
        db.getDesign().getWiringPropertiesElements(),
        exporter,
        exportedUnids,
        skipped);
    exportNamedSafe(
        elements,
        "compositeapplication",
        db.getDesign().getCompositeApplications(),
        exporter,
        exportedUnids,
        skipped);
    exportNamedSafe(
        elements,
        "compositecomponent",
        db.getDesign().getCompositeComponents(),
        exporter,
        exportedUnids,
        skipped);
    exportBulkOnly(elements, db, exporter, exportedUnids, skipped);
    forgetRecoveredSkips(elements, skipped);

    return elements;
  }

  /**
   * Delete one bulk/residual design note by UNID and/or name. Used by {@code PUT /designdxl} when
   * an element has {@code "delete": true}.
   */
  static JsonObject deleteOne(Database db, String type, String name, String unid)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (!DesignBulkTypes.isKnown(t)) {
      throw new KeepExceptionWrongDataRequest("Unknown design element type: " + type);
    }
    int deleted = 0;
    if (unid != null && !unid.isBlank()) {
      Optional<Document> doc = db.getDocumentByUNID(unid.trim());
      if (doc.isEmpty()) {
        throw new KeepExceptionWrongDataRequest("Design note not found for unid: " + unid);
      }
      doc.get().delete();
      deleted = 1;
    } else if (name != null && !name.isBlank()) {
      // Delete all notes with this title (clears DXL replace duplicates)
      while (true) {
        Optional<BulkOnlyNotes.Entry> entry = BulkOnlyNotes.find(db, t, name);
        if (entry.isEmpty()) {
          entry = BulkOnlyNotes.find(db, "", name);
        }
        if (entry.isEmpty()) {
          break;
        }
        entry.get().document().delete();
        deleted++;
        if (deleted > 50) {
          break;
        }
      }
      if (deleted == 0) {
        throw new KeepExceptionWrongDataRequest(t + " not found: " + name);
      }
    } else {
      throw new KeepExceptionWrongDataRequest("name or unid is required to delete");
    }
    JsonObject result =
        new JsonObject().put("success", true).put("type", t).put("deleted", deleted);
    if (name != null && !name.isBlank()) {
      result.put("name", name);
    }
    if (unid != null && !unid.isBlank()) {
      result.put("unid", unid.trim());
    }
    return result;
  }

  /**
   * Import one element. Returns result object with success/type/name/log.
   *
   * @param session unused (call-site compatibility); may be null
   */
  static JsonObject importOne(
      DxlImporter importer, Database db, String type, String dxl, KeepJnxSession session)
      throws Exception {
    return importOne(importer, db, type, dxl, session, DxlDesignPatch.Mode.UPSERT);
  }

  static JsonObject importOne(
      DxlImporter importer,
      Database db,
      String type,
      String dxl,
      KeepJnxSession session,
      DxlDesignPatch.Mode mode)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (!DesignBulkTypes.isKnown(t) && !"dbicon".equals(t)) {
      throw new KeepExceptionWrongDataRequest("Unknown design element type: " + type);
    }
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl is required for type " + t);
    }
    if (mode == null) {
      mode = DxlDesignPatch.Mode.UPSERT;
    }

    String name = null;
    String log;
    String signWarning = null;

    switch (t) {
      case "acl" -> {
        DesignDxl.requireRootOnly(dxl, "acl");
        log = DxlDesignPatch.importAcl(importer, dxl, db);
        DesignAcl.ensureDomainManagers(db);
      }
      case "applicationproperties" -> {
        String root = DesignDxl.requireRootOnlyReturningLocalName(dxl, "note", "database");
        if ("database".equalsIgnoreCase(root)) {
          log = DxlDesignPatch.importWithDbProperties(importer, dxl, db, mode);
        } else {
          log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        }
        DbProperties props = db.getDesign().getDatabaseProperties();
        if (props == null || props.getDocument() == null) {
          throw new KeepExceptionServer(
              "Application Properties missing after DXL import; cannot sign");
        }
        signWarning = safeSign(props);
      }
      case "about" -> {
        DesignDxl.requireRootOnly(dxl, "helpaboutdocument");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        AboutDocument el =
            db.getDesign()
                .getAboutDocument()
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "AboutDocument missing after DXL import; cannot sign"));
        signWarning = safeSign(el);
      }
      case "using" -> {
        DesignDxl.requireRootOnly(dxl, "helpusingdocument");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        UsingDocument el =
            db.getDesign()
                .getUsingDocument()
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "UsingDocument missing after DXL import; cannot sign"));
        signWarning = safeSign(el);
      }
      case "sharedactions" -> {
        name = DesignDxl.requireSharedActionsName(dxl);
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        final String sharedActionsName = name;
        SharedActions el =
            SharedActionsNotes.find(db, sharedActionsName)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "SharedActions %s missing after DXL import; cannot sign",
                            sharedActionsName));
        signWarning = safeSign(el);
      }
      case "databasescript" -> {
        DesignDxl.requireRootOnly(dxl, "databasescript");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        DatabaseScriptLibrary el =
            db.getDesign()
                .getDatabaseScriptLibrary()
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "DatabaseScript missing after DXL import; cannot sign"));
        signWarning = safeSign(el);
      }
      case "image" -> {
        name = DesignDxl.requireRootName(dxl, "imageresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        if (DesignElementLookup.isDbIconName(name)) {
          DbIcon.allowDesignRefresh(db);
        }
        signWarning = signNamed(db.getDesign().getImageResource(name), "ImageResource", name);
      }
      case "fileresource" -> {
        name = DesignDxl.requireRootName(dxl, "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getFileResource(name), "FileResource", name);
      }
      case "stylesheet" -> {
        name = DesignDxl.requireRootName(dxl, "stylesheetresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getStyleSheet(name), "StyleSheet", name);
      }
      case "theme" -> {
        name = DesignDxl.requireDesignName(dxl, "theme", "note");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getTheme(name), "Theme", name);
      }
      case "scriptlibrary" -> {
        name = DesignDxl.requireRootName(dxl, "scriptlibrary");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        Optional<ScriptLibrary> lib = db.getDesign().getScriptLibrary(name);
        if (dxl.toLowerCase(Locale.ROOT).contains("<lotusscript")) {
          compileLotusScript(lib.orElse(null), "ScriptLibrary", name);
          signWarning = signNamed(lib, "ScriptLibrary", name);
        } else {
          // Design-element sign/save rewrites the library and drops JavaScript, SSJS and Java.
          signWarning = signRawNote(lib, "ScriptLibrary", name);
        }
      }
      case "sharedfield" -> {
        name = DesignDxl.requireRootName(dxl, "sharedfield");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getSharedField(name), "SharedField", name);
      }
      case "sharedcolumn" -> {
        name = DesignDxl.requireRootName(dxl, "sharedcolumn");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getSharedColumn(name), "SharedColumn", name);
      }
      case "subform" -> {
        name = DesignDxl.requireRootName(dxl, "subform");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getSubform(name), "Subform", name);
      }
      case "form" -> {
        name = DesignDxl.requireRootName(dxl, "form");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getForm(name), "Form", name);
      }
      case "page" -> {
        name = DesignDxl.requireRootName(dxl, "page");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getPage(name), "Page", name);
      }
      case "outline" -> {
        name = DesignDxl.requireRootName(dxl, "outline");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getOutline(name), "Outline", name);
      }
      case "frameset" -> {
        name = DesignDxl.requireRootName(dxl, "frameset");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        try {
          signWarning = signNamed(db.getDesign().getFrameset(name), "Frameset", name);
        } catch (RuntimeException e) {
          // JNX FramesetImpl Optional.empty version — import succeeded, sign skipped
          signWarning = e.getMessage() == null ? e.toString() : e.getMessage();
        }
      }
      case "view" -> {
        name = DesignDxl.requireRootName(dxl, "view");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getView(name), "View", name);
      }
      case "folder" -> {
        name = DesignDxl.requireRootName(dxl, "folder");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getFolder(name), "Folder", name);
      }
      case "agent" -> {
        name = DesignDxl.requireRootName(dxl, "agent");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        Optional<DesignAgent> agent = db.getDesign().getAgent(name);
        if (dxl.toLowerCase(Locale.ROOT).contains("<lotusscript")) {
          compileLotusScript(agent.orElse(null), "Agent", name);
        }
        signWarning = signNamed(agent, "Agent", name);
      }
      case "customcontrol" -> {
        String requested = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        CustomControls.Entry entry =
            CustomControls.find(db, requested)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "CustomControl %s missing after DXL import; cannot sign", requested));
        name = entry.name();
        final String resolvedName = name;
        DesignElement el =
            CustomControls.asDesignElement(db, entry.document())
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "CustomControl %s not loadable as DesignElement after import",
                            resolvedName));
        signWarning = safeSign(el);
      }
      case "webservice" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "webservice", "agent");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        WebServices.Entry entry =
            WebServices.find(db, WebServices.Kind.PROVIDER, requested)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "WebServiceProvider %s missing after DXL import; cannot sign",
                            requested));
        name = entry.name();
        final String resolvedName = name;
        DesignElement el =
            WebServices.asDesignElement(db, entry.document())
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "WebServiceProvider %s not loadable as DesignElement after import",
                            resolvedName));
        signWarning = safeSign(el);
      }
      case "webserviceconsumer" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "scriptlibrary", "webservice");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        WebServices.Entry entry =
            WebServices.find(db, WebServices.Kind.CONSUMER, requested)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "WebServiceConsumer %s missing after DXL import; cannot sign",
                            requested));
        name = entry.name();
        final String resolvedName = name;
        DesignElement el =
            WebServices.asDesignElement(db, entry.document())
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "WebServiceConsumer %s not loadable as DesignElement after import",
                            resolvedName));
        signWarning = safeSign(el);
      }
      case "xpage" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource", "xpage");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getXPage(name), "XPage", name);
      }
      case "wiringproperties" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning =
            signNamed(
                db.getDesign().getWiringPropertiesElement(name), "WiringProperties", name);
      }
      case "compositeapplication" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning =
            signNamed(
                db.getDesign().getCompositeApplication(name), "CompositeApplication", name);
      }
      case "compositecomponent" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning =
            signNamed(
                db.getDesign().getCompositeComponent(name), "CompositeComponent", name);
      }
      case "dataconnection" -> {
        String requestedDc =
            DesignDxl.requireDesignName(
                dxl, "dataconnection", "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        BulkOnlyNotes.Entry entry =
            BulkOnlyNotes.find(db, "dataconnection", requestedDc)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "dataconnection %s missing after DXL import; cannot sign",
                            requestedDc));
        name = entry.name();
        final String resolvedName = name;
        Optional<DesignElement> elOpt = BulkOnlyNotes.asDesignElement(db, entry.document());
        if (elOpt.isPresent()) {
          signWarning = safeSign(elOpt.get());
        } else {
          try {
            entry.document().sign();
          } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("signature")
                || lower.contains("0x2dc")
                || lower.contains("2dc")) {
              signWarning = message;
            } else {
              throw new KeepExceptionServer(
                  "dataconnection %s sign failed: %s", resolvedName, message);
            }
          }
        }
      }
      case "navigator" -> {
        name = DesignDxl.requireDesignName(dxl, "navigator", "note");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getNavigator(name), "Navigator", name);
      }
      case "jar", "applet", "javasource", "xspproperties", "xpagesresource", "designnote" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "navigator", "webservice", "agent", "appletresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        final String bulkType = t;
        BulkOnlyNotes.Entry entry =
            BulkOnlyNotes.find(db, bulkType, requested)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "%s %s missing after DXL import; cannot sign",
                            bulkType, requested));
        name = entry.name();
        final String resolvedName = name;
        Optional<DesignElement> elOpt = BulkOnlyNotes.asDesignElement(db, entry.document());
        if (elOpt.isPresent()) {
          signWarning = safeSign(elOpt.get());
        } else {
          // Residual notes may not map to a JNX DesignElement; sign the document directly
          try {
            entry.document().sign();
          } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("signature")
                || lower.contains("0x2dc")
                || lower.contains("2dc")) {
              signWarning = message;
            } else {
              throw new KeepExceptionServer(
                  "%s %s sign failed: %s", bulkType, resolvedName, message);
            }
          }
        }
      }
      default -> throw new KeepExceptionWrongDataRequest("Unsupported type: " + t);
    }

    JsonObject result =
        new JsonObject().put("success", true).put("type", t).put("log", log == null ? "" : log);
    if (name != null) {
      result.put("name", name);
    }
    if (signWarning != null && !signWarning.isBlank()) {
      result.put("signWarning", signWarning);
    }
    return result;
  }

  static List<JsonObject> sortForImport(JsonArray elements) throws KeepExceptionWrongDataRequest {
    List<JsonObject> list = new ArrayList<>();
    if (elements == null) {
      return list;
    }
    for (int i = 0; i < elements.size(); i++) {
      Object o = elements.getValue(i);
      if (!(o instanceof JsonObject jo)) {
        throw new KeepExceptionWrongDataRequest("elements[" + i + "] must be an object");
      }
      list.add(jo);
    }
    list.sort(
        Comparator.comparingInt(
                (JsonObject jo) -> DesignBulkTypes.importOrder(jo.getString("type")))
            .thenComparing(jo -> jo.getString("name", ""), String.CASE_INSENSITIVE_ORDER));
    return list;
  }

  @FunctionalInterface
  private interface SingletonExport {
    Optional<String> export() throws Exception;
  }

  private static void exportSingleton(JsonArray elements, String type, SingletonExport exporter)
      throws Exception {
    Optional<String> dxl = exporter.export();
    if (dxl.isEmpty() || dxl.get().isBlank()) {
      return;
    }
    elements.add(new JsonObject().put("type", type).put("dxl", dxl.get()));
  }

  private static void exportSharedActions(
      JsonArray elements, Database db, DxlExporter exporter, Set<String> exportedUnids)
      throws Exception {
    List<SharedActions> list = SharedActionsNotes.list(db);
    // Also export notes that lack a resolvable name (rare) so they are not lost.
    List<SharedActions> unnamed =
        SharedActionsNotes.stream(db)
            .filter(el -> SharedActionsNotes.nameOf(el) == null)
            .toList();
    for (SharedActions el : list) {
      trackUnid(exportedUnids, el.getDocument());
      elements.add(
          new JsonObject()
              .put("type", "sharedactions")
              .put("name", SharedActionsNotes.nameOf(el))
              .put("dxl", exporter.exportDocument(el.getDocument())));
    }
    for (SharedActions el : unnamed) {
      trackUnid(exportedUnids, el.getDocument());
      elements.add(
          new JsonObject()
              .put("type", "sharedactions")
              .put("dxl", exporter.exportDocument(el.getDocument())));
    }
  }

  private static void exportNamed(
      JsonArray elements,
      String type,
      Stream<? extends DesignElement.NamedDesignElement> stream,
      DxlExporter exporter,
      Set<String> exportedUnids)
      throws Exception {
    List<? extends DesignElement.NamedDesignElement> list =
        stream
            .filter(el -> el.getTitle() != null && !el.getTitle().isBlank())
            .sorted(
                Comparator.comparing(
                    DesignElement.NamedDesignElement::getTitle, String.CASE_INSENSITIVE_ORDER))
            .toList();
    for (DesignElement.NamedDesignElement el : list) {
      trackUnid(exportedUnids, el.getDocument());
      elements.add(
          new JsonObject()
              .put("type", type)
              .put("name", el.getTitle())
              .put("dxl", exporter.exportDocument(el.getDocument())));
    }
  }

  /**
   * Like {@link #exportNamed} but tolerates JNX wrappers that throw while iterating (e.g. framesets
   * with unsupported/missing version). Failed notes are left for {@link #exportBulkOnly}.
   */
  private static void exportNamedSafe(
      JsonArray elements,
      String type,
      Stream<? extends DesignElement.NamedDesignElement> stream,
      DxlExporter exporter,
      Set<String> exportedUnids,
      JsonArray skipped) {
    List<DesignElement.NamedDesignElement> list = new ArrayList<>();
    try {
      var it = stream.iterator();
      while (true) {
        DesignElement.NamedDesignElement el;
        try {
          if (!it.hasNext()) {
            break;
          }
          el = it.next();
        } catch (RuntimeException failed) {
          recordSkip(skipped, type, null, failed);
          continue;
        }
        try {
          if (el != null && el.getTitle() != null && !el.getTitle().isBlank()) {
            list.add(el);
          }
        } catch (RuntimeException failed) {
          recordSkip(skipped, type, titleOrNull(el), failed);
        }
      }
    } catch (RuntimeException failed) {
      // entire enumeration failed; bulk catch-all may still export raw notes
      recordSkip(skipped, type, null, failed);
      return;
    }
    list.sort(
        Comparator.comparing(
            DesignElement.NamedDesignElement::getTitle, String.CASE_INSENSITIVE_ORDER));
    for (DesignElement.NamedDesignElement el : list) {
      try {
        Document doc = el.getDocument();
        String dxl = exporter.exportDocument(doc);
        trackUnid(exportedUnids, doc);
        elements.add(
            new JsonObject().put("type", type).put("name", el.getTitle()).put("dxl", dxl));
      } catch (Exception failed) {
        // leave UNID untracked so exportBulkOnly can try raw DXL
        recordSkip(skipped, type, titleOrNull(el), failed);
      }
    }
  }

  private static String titleOrNull(DesignElement.NamedDesignElement el) {
    if (el == null) {
      return null;
    }
    try {
      String title = el.getTitle();
      return title == null || title.isBlank() ? null : title;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static void recordSkip(JsonArray skipped, String type, String name, Throwable error) {
    if (skipped == null || error == null) {
      return;
    }
    String message = error.getMessage() == null ? error.toString() : error.getMessage();
    JsonObject row = new JsonObject().put("type", type).put("message", message);
    if (name != null && !name.isBlank()) {
      row.put("name", name);
    }
    skipped.add(row);
  }

  /** Drop a typed skip when that name was exported anyway (often as designnote). */
  private static void forgetRecoveredSkips(JsonArray elements, JsonArray skipped) {
    if (skipped == null || skipped.isEmpty()) {
      return;
    }
    Set<String> names = new HashSet<>();
    for (int i = 0; i < elements.size(); i++) {
      String name = elements.getJsonObject(i).getString("name");
      if (name != null && !name.isBlank()) {
        names.add(name.trim().toLowerCase(Locale.ROOT));
      }
    }
    for (int i = skipped.size() - 1; i >= 0; i--) {
      String name = skipped.getJsonObject(i).getString("name");
      if (name != null && !name.isBlank() && names.contains(name.trim().toLowerCase(Locale.ROOT))) {
        skipped.remove(i);
      }
    }
  }

  private static void exportCustomControls(
      JsonArray elements, Database db, DxlExporter exporter, Set<String> exportedUnids)
      throws Exception {
    for (CustomControls.Entry entry : CustomControls.list(db)) {
      trackUnid(exportedUnids, entry.document());
      elements.add(
          new JsonObject()
              .put("type", "customcontrol")
              .put("name", entry.name())
              .put("dxl", exporter.exportDocument(entry.document())));
    }
  }

  private static void exportWebServices(
      JsonArray elements, Database db, DxlExporter exporter, Set<String> exportedUnids)
      throws Exception {
    for (WebServices.Entry entry : WebServices.list(db, WebServices.Kind.PROVIDER)) {
      trackUnid(exportedUnids, entry.document());
      elements.add(
          new JsonObject()
              .put("type", "webservice")
              .put("name", entry.name())
              .put("dxl", exporter.exportDocument(entry.document())));
    }
    for (WebServices.Entry entry : WebServices.list(db, WebServices.Kind.CONSUMER)) {
      trackUnid(exportedUnids, entry.document());
      elements.add(
          new JsonObject()
              .put("type", "webserviceconsumer")
              .put("name", entry.name())
              .put("dxl", exporter.exportDocument(entry.document())));
    }
  }

  /** Script libraries excluding Web Service Consumers (those use type webserviceconsumer). */
  private static void exportScriptLibraries(
      JsonArray elements, Database db, DxlExporter exporter, Set<String> exportedUnids)
      throws Exception {
    List<? extends DesignElement.NamedDesignElement> list =
        db.getDesign()
            .getScriptLibraries()
            .filter(el -> el.getTitle() != null && !el.getTitle().isBlank())
            .filter(el -> !WebServices.isConsumer(el.getDocument()))
            .sorted(
                Comparator.comparing(
                    DesignElement.NamedDesignElement::getTitle, String.CASE_INSENSITIVE_ORDER))
            .toList();
    for (DesignElement.NamedDesignElement el : list) {
      trackUnid(exportedUnids, el.getDocument());
      elements.add(
          new JsonObject()
              .put("type", "scriptlibrary")
              .put("name", el.getTitle())
              .put("dxl", exporter.exportDocument(el.getDocument())));
    }
  }

  private static void exportBulkOnly(
      JsonArray elements,
      Database db,
      DxlExporter exporter,
      Set<String> exportedUnids,
      JsonArray skipped)
      throws Exception {
    for (BulkOnlyNotes.Entry entry : BulkOnlyNotes.list(db, exportedUnids, skipped)) {
      try {
        String dxl = exporter.exportDocument(entry.document());
        if (!hasExportableDxl(dxl)) {
          // DXLExporter sometimes returns only an XML prolog for notes COM
          // SelectAllDesignElements also omits — keep bulk identical to COM.
          continue;
        }
        trackUnid(exportedUnids, entry.document());
        elements.add(
            new JsonObject()
                .put("type", entry.type())
                .put("name", entry.name())
                .put("dxl", dxl));
      } catch (Exception failed) {
        recordSkip(skipped, entry.type(), entry.name(), failed);
      }
    }
  }

  /** True when DXL contains a real note (not prolog-only / whitespace). */
  private static boolean hasExportableDxl(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return false;
    }
    String s = dxl.trim();
    if (s.regionMatches(true, 0, "<?xml", 0, 5)) {
      int end = s.indexOf("?>");
      if (end >= 0) {
        s = s.substring(end + 2).trim();
      }
    }
    if (s.isEmpty()) {
      return false;
    }
    String lower = s.toLowerCase(Locale.ROOT);
    return lower.contains("<note")
        || lower.contains("<database")
        || lower.contains("<form")
        || lower.contains("<view")
        || lower.contains("<agent")
        || lower.contains("<page")
        || lower.contains("<subform")
        || lower.contains("<outline")
        || lower.contains("<frameset")
        || lower.contains("<folder")
        || lower.contains("<imageresource")
        || lower.contains("<scriptlibrary")
        || lower.contains("<sharedactions")
        || lower.contains("<sharedfield")
        || lower.contains("<fileresource");
  }

  private static void trackUnid(Set<String> exportedUnids, Document doc) {
    if (exportedUnids == null || doc == null) {
      return;
    }
    try {
      String unid = doc.getUNID();
      if (unid != null && !unid.isBlank()) {
        exportedUnids.add(unid);
      }
    } catch (Exception ignored) {
      // ignore
    }
  }

  /** Compile LotusScript so a later run executes this source, not an empty module. */
  private static void compileLotusScript(DesignElement el, String label, String name)
      throws KeepExceptionServer {
    if (el == null) {
      throw new KeepExceptionServer("%s %s missing after DXL import; cannot compile", label, name);
    }
    try {
      Document doc = el.getDocument();
      doc.compileLotusScript();
      doc.save();
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionServer("LotusScript compile failed: %s", message);
    }
  }

  /**
   * @return warning message if sign failed soft; null on success
   */
  /** Sign the note itself. Design-element save drops non-LotusScript library source. */
  private static String signRawNote(
      Optional<? extends DesignElement> opt, String label, String name)
      throws KeepExceptionServer {
    DesignElement el =
        opt.orElseThrow(
            () ->
                new KeepExceptionServer(
                    "%s %s missing after DXL import; cannot sign", label, name));
    try {
      Document doc = el.getDocument();
      doc.sign();
      doc.save();
      return null;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        return message;
      }
      throw new KeepExceptionServer("Sign failed: %s", message);
    }
  }

  private static String signNamed(
      Optional<? extends DesignElement> opt, String label, String name)
      throws KeepExceptionServer {
    DesignElement el =
        opt.orElseThrow(
            () ->
                new KeepExceptionServer(
                    "%s %s missing after DXL import; cannot sign", label, name));
    return safeSign(el);
  }

  /**
   * Sign + save. Soft-fails on inconsistent DXL signatures (0x2dc) so bulk import can continue.
   *
   * @return null on success, else warning text
   */
  private static String safeSign(DesignElement el) throws KeepExceptionServer {
    try {
      el.sign();
      el.save();
      return null;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        try {
          el.save();
        } catch (Exception ignored) {
          // imported DXL may still be usable unsigned
        }
        return message;
      }
      throw new KeepExceptionServer("Sign failed: %s", message);
    }
  }
}
