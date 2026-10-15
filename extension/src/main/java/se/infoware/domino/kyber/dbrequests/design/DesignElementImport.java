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
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Import of one design element, and delete of one bulk note.
 *
 * <p>Each import goes through {@link DxlDesignPatch}, which drops {@code replicaid}, and through
 * {@link DesignAccess} when the element needs a privilege checkbox. Export of the same notes is
 * {@link DesignElementExport}.
 */
final class DesignElementImport {

  private DesignElementImport() {}

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
   * @param session logged-in KEEP session used for privilege checks; may be null
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
    if (session != null) {
      DesignAccess.requireWritePrivileges(session, db, t, dxl);
    }

    String name = null;
    String log;
    String signWarning = null;

    switch (t) {
      case "acl" -> {
        DesignDxl.requireRootOnly(dxl, "acl");
        log = DxlDesignPatch.importAcl(importer, dxl, db);
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
        signWarning = safeSign(props, dxl);
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
        signWarning = safeSign(el, dxl);
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
        signWarning = safeSign(el, dxl);
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
        signWarning = safeSign(el, dxl);
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
        signWarning = safeSign(el, dxl);
      }
      case "image" -> {
        name = DesignDxl.requireRootName(dxl, "imageresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        if (DesignElementLookup.isDbIconName(name)) {
          DbIcon.allowDesignRefresh(db);
        }
        signWarning = signNamed(db.getDesign().getImageResource(name), "ImageResource", name, dxl);
      }
      case "fileresource" -> {
        name = DesignDxl.requireRootName(dxl, "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getFileResource(name), "FileResource", name, dxl);
      }
      case "stylesheet" -> {
        name = DesignDxl.requireRootName(dxl, "stylesheetresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getStyleSheet(name), "StyleSheet", name, dxl);
      }
      case "theme" -> {
        name = DesignDxl.requireDesignName(dxl, "theme", "note");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getTheme(name), "Theme", name, dxl);
      }
      case "scriptlibrary" -> {
        name = DesignDxl.requireRootName(dxl, "scriptlibrary");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        Optional<ScriptLibrary> lib = db.getDesign().getScriptLibrary(name);
        if (dxl.toLowerCase(Locale.ROOT).contains("<lotusscript")) {
          compileLotusScript(lib.orElse(null), "ScriptLibrary", name);
          signWarning = signNamed(lib, "ScriptLibrary", name, dxl);
        } else {
          // Design-element sign/save rewrites the library and drops JavaScript, SSJS and Java.
          signWarning = signRawNote(lib, "ScriptLibrary", name, dxl);
        }
      }
      case "sharedfield" -> {
        name = DesignDxl.requireRootName(dxl, "sharedfield");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getSharedField(name), "SharedField", name, dxl);
      }
      case "sharedcolumn" -> {
        name = DesignDxl.requireRootName(dxl, "sharedcolumn");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getSharedColumn(name), "SharedColumn", name, dxl);
      }
      case "subform" -> {
        name = DesignDxl.requireRootName(dxl, "subform");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getSubform(name), "Subform", name, dxl);
      }
      case "form" -> {
        name = DesignDxl.requireRootName(dxl, "form");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getForm(name), "Form", name, dxl);
      }
      case "page" -> {
        name = DesignDxl.requireRootName(dxl, "page");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getPage(name), "Page", name, dxl);
      }
      case "outline" -> {
        name = DesignDxl.requireRootName(dxl, "outline");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getOutline(name), "Outline", name, dxl);
      }
      case "frameset" -> {
        name = DesignDxl.requireRootName(dxl, "frameset");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        try {
          signWarning = signNamed(db.getDesign().getFrameset(name), "Frameset", name, dxl);
        } catch (RuntimeException e) {
          // JNX FramesetImpl Optional.empty version — import succeeded, sign skipped
          signWarning = e.getMessage() == null ? e.toString() : e.getMessage();
        }
      }
      case "view" -> {
        name = DesignDxl.requireRootName(dxl, "view");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getView(name), "View", name, dxl);
      }
      case "folder" -> {
        name = DesignDxl.requireRootName(dxl, "folder");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getFolder(name), "Folder", name, dxl);
      }
      case "agent" -> {
        name = DesignDxl.requireRootName(dxl, "agent");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        Optional<DesignAgent> agent = db.getDesign().getAgent(name);
        if (dxl.toLowerCase(Locale.ROOT).contains("<lotusscript")) {
          compileLotusScript(agent.orElse(null), "Agent", name);
        }
        signWarning = signNamed(agent, "Agent", name, dxl);
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
        signWarning = safeSign(el, dxl);
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
        signWarning = safeSign(el, dxl);
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
        signWarning = safeSign(el, dxl);
      }
      case "xpage" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource", "xpage");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getXPage(name), "XPage", name, dxl);
      }
      case "wiringproperties" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning =
            signNamed(
                db.getDesign().getWiringPropertiesElement(name), "WiringProperties", name, dxl);
      }
      case "compositeapplication" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning =
            signNamed(
                db.getDesign().getCompositeApplication(name), "CompositeApplication", name, dxl);
      }
      case "compositecomponent" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning =
            signNamed(
                db.getDesign().getCompositeComponent(name), "CompositeComponent", name, dxl);
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
          signWarning = safeSign(elOpt.get(), dxl);
        } else {
          signWarning = signStampedDocument(entry.document(), "dataconnection", resolvedName, dxl);
        }
      }
      case "navigator" -> {
        name = DesignDxl.requireDesignName(dxl, "navigator", "note");
        log = DxlDesignPatch.importDesign(importer, dxl, db, mode);
        signWarning = signNamed(db.getDesign().getNavigator(name), "Navigator", name, dxl);
      }
      case "jar", "applet", "javasource", "webcontent", "xspproperties", "xpagesresource", "designnote" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "navigator", "webservice", "agent", "appletresource");
        if ("webcontent".equals(t)) {
          WebContent.requireOutsideWebInf(requested);
        }
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
          signWarning = safeSign(elOpt.get(), dxl);
        } else {
          // Residual notes may not map to a JNX DesignElement; sign the document directly
          signWarning = signStampedDocument(entry.document(), bulkType, resolvedName, dxl);
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
   * Sign the note itself. Design-element save drops non-LotusScript library source.
   *
   * @return warning message if sign failed soft; null on success
   */
  private static String signRawNote(
      Optional<? extends DesignElement> opt, String label, String name, String dxl)
      throws KeepExceptionServer {
    DesignElement el =
        opt.orElseThrow(
            () ->
                new KeepExceptionServer(
                    "%s %s missing after DXL import; cannot sign", label, name));
    try {
      Document doc = el.getDocument();
      DesignComment.stamp(doc, dxl);
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
      Optional<? extends DesignElement> opt, String label, String name, String dxl)
      throws KeepExceptionServer {
    DesignElement el =
        opt.orElseThrow(
            () ->
                new KeepExceptionServer(
                    "%s %s missing after DXL import; cannot sign", label, name));
    return safeSign(el, dxl);
  }

  /**
   * Sign + save. Soft-fails on inconsistent DXL signatures (0x2dc) so bulk import can continue.
   *
   * @return null on success, else warning text
   */
  private static String signStampedDocument(Document doc, String type, String name, String dxl)
      throws KeepExceptionServer {
    try {
      DesignComment.stamp(doc, dxl);
      doc.sign();
      doc.save();
      return null;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        try {
          doc.save();
        } catch (Exception ignored) {
          // imported DXL may still be usable unsigned
        }
        return message;
      }
      throw new KeepExceptionServer("%s %s sign failed: %s", type, name, message);
    }
  }

  private static String safeSign(DesignElement el, String dxl) throws KeepExceptionServer {
    try {
      DesignComment.stamp(el, dxl);
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
