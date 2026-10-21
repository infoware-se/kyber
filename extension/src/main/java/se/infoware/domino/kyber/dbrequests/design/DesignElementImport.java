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
   * Delete one bulk/residual design note by name. Used by {@code PUT /import} when an element has
   * {@code "delete": true}. A nameless note is named {@code unid:} plus the note id, the same name
   * the export uses.
   */
  static JsonObject deleteOne(Database db, String type, String name) throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (!DesignBulkTypes.isKnown(t)) {
      throw new KeepExceptionWrongDataRequest("Unknown design element type: " + type);
    }
    int deleted = 0;
    if (name != null && !name.isBlank()) {
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
      throw new KeepExceptionWrongDataRequest("name is required to delete");
    }
    return new JsonObject()
        .put("success", true)
        .put("type", t)
        .put("deleted", deleted)
        .put("name", name);
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
    CommentUpdate comments =
        CommentUpdate.preserve(
            DesignElementLookup.existingComment(db, t, DesignDxl.designName(dxl)));
    WriteTrace trace = new WriteTrace();

    switch (t) {
      case "acl" -> {
        DesignDxl.requireRootOnly(dxl, "acl");
        DxlDesignPatch.importAcl(importer, dxl, db);
      }
      case "applicationproperties" -> {
        String root = DesignDxl.requireRootOnlyReturningLocalName(dxl, "note", "database");
        if ("database".equalsIgnoreCase(root)) {
        DxlDesignPatch.importWithDbProperties(importer, dxl, db, mode);
        } else {
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        }
        DbProperties props = db.getDesign().getDatabaseProperties();
        if (props == null || props.getDocument() == null) {
          throw new KeepExceptionServer(
              "Application Properties missing after DXL import; cannot sign");
        }
        trace.accept(safeSign(props, dxl, comments));
      }
      case "about" -> {
        DesignDxl.requireRootOnly(dxl, "helpaboutdocument");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        AboutDocument el =
            db.getDesign()
                .getAboutDocument()
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "AboutDocument missing after DXL import; cannot sign"));
        trace.accept(safeSign(el, dxl, comments));
      }
      case "using" -> {
        DesignDxl.requireRootOnly(dxl, "helpusingdocument");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        UsingDocument el =
            db.getDesign()
                .getUsingDocument()
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "UsingDocument missing after DXL import; cannot sign"));
        trace.accept(safeSign(el, dxl, comments));
      }
      case "sharedactions" -> {
        name = DesignDxl.requireSharedActionsName(dxl);
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        final String sharedActionsName = name;
        SharedActions el =
            SharedActionsNotes.find(db, sharedActionsName)
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "SharedActions %s missing after DXL import; cannot sign",
                            sharedActionsName));
        trace.accept(safeSign(el, dxl, comments));
      }
      case "databasescript" -> {
        DesignDxl.requireRootOnly(dxl, "databasescript");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        DatabaseScriptLibrary el =
            db.getDesign()
                .getDatabaseScriptLibrary()
                .orElseThrow(
                    () ->
                        new KeepExceptionServer(
                            "DatabaseScript missing after DXL import; cannot sign"));
        trace.accept(safeSign(el, dxl, comments));
      }
      case "image" -> {
        name = DesignDxl.requireRootName(dxl, "imageresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        if (DesignElementLookup.isDbIconName(name)) {
          DbIcon.allowDesignRefresh(db);
          WorkspaceIcon.sync(db, importer, dxl);
        }
        trace.accept(signNamed(db.getDesign().getImageResource(name), "ImageResource", name, dxl, comments));
      }
      case "fileresource" -> {
        name = DesignDxl.requireRootName(dxl, "fileresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getFileResource(name), "FileResource", name, dxl, comments));
      }
      case "stylesheet" -> {
        name = DesignDxl.requireRootName(dxl, "stylesheetresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getStyleSheet(name), "StyleSheet", name, dxl, comments));
      }
      case "theme" -> {
        name = DesignDxl.requireDesignName(dxl, "theme", "note");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getTheme(name), "Theme", name, dxl, comments));
      }
      case "scriptlibrary" -> {
        name = DesignDxl.requireRootName(dxl, "scriptlibrary");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        Optional<ScriptLibrary> lib = db.getDesign().getScriptLibrary(name);
        if (dxl.toLowerCase(Locale.ROOT).contains("<lotusscript")) {
          compileLotusScript(lib.orElse(null), "ScriptLibrary", name);
          trace.accept(signNamed(lib, "ScriptLibrary", name, dxl, comments));
        } else if (dxl.toLowerCase(Locale.ROOT).contains("<javaproject")
            && !dxl.contains("%%object%%.jar")) {
          // sign() stores an empty %%object%%.jar. Designer then reports compilation errors
          // until the project is cleaned. A later import that includes the compiled jar is signed.
          trace.accept(saveRawNote(lib, "ScriptLibrary", name, dxl, comments));
        } else {
          // Design-element sign/save rewrites the library and drops JavaScript, SSJS and Java.
          trace.accept(signRawNote(lib, "ScriptLibrary", name, dxl, comments));
        }
      }
      case "sharedfield" -> {
        name = DesignDxl.requireRootName(dxl, "sharedfield");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getSharedField(name), "SharedField", name, dxl, comments));
      }
      case "sharedcolumn" -> {
        name = DesignDxl.requireRootName(dxl, "sharedcolumn");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getSharedColumn(name), "SharedColumn", name, dxl, comments));
      }
      case "subform" -> {
        name = DesignDxl.requireRootName(dxl, "subform");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getSubform(name), "Subform", name, dxl, comments));
      }
      case "form" -> {
        name = DesignDxl.requireRootName(dxl, "form");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getForm(name), "Form", name, dxl, comments));
      }
      case "page" -> {
        name = DesignDxl.requireRootName(dxl, "page");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getPage(name), "Page", name, dxl, comments));
      }
      case "outline" -> {
        name = DesignDxl.requireRootName(dxl, "outline");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getOutline(name), "Outline", name, dxl, comments));
      }
      case "frameset" -> {
        name = DesignDxl.requireRootName(dxl, "frameset");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        try {
          trace.accept(signNamed(db.getDesign().getFrameset(name), "Frameset", name, dxl, comments));
        } catch (RuntimeException e) {
          // JNX FramesetImpl Optional.empty version — import succeeded, sign skipped
          trace.warningOnly(e.getMessage() == null ? e.toString() : e.getMessage());
        }
      }
      case "view" -> {
        name = DesignDxl.requireRootName(dxl, "view");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getView(name), "View", name, dxl, comments));
      }
      case "folder" -> {
        name = DesignDxl.requireRootName(dxl, "folder");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getFolder(name), "Folder", name, dxl, comments));
      }
      case "agent" -> {
        name = DesignDxl.requireRootName(dxl, "agent");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        Optional<DesignAgent> agent = db.getDesign().getAgent(name);
        if (dxl.toLowerCase(Locale.ROOT).contains("<lotusscript")) {
          compileLotusScript(agent.orElse(null), "Agent", name);
        }
        trace.accept(signNamed(agent, "Agent", name, dxl, comments));
      }
      case "customcontrol" -> {
        String requested = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
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
        // Design-element sign/save rewrites the note and drops $ClassData0.
        SignOutcome signed = signRawNote(Optional.of(el), "CustomControl", name, dxl, comments);
        dropOrphanXspClasses(
            CustomControls.find(db, name)
                .flatMap(found -> CustomControls.asDesignElement(db, found.document())));
        trace.accept(signed);
      }
      case "webservice" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "webservice", "agent");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
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
        trace.accept(safeSign(el, dxl, comments));
      }
      case "webserviceconsumer" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "scriptlibrary", "webservice");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
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
        trace.accept(safeSign(el, dxl, comments));
      }
      case "xpage" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource", "xpage");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        // sign() rewrites the note and drops $ClassData0. HTTP then cannot find the page.
        if (dxl.contains("$ClassData0")) {
          trace.accept(SignOutcome.signed());
        } else {
          SignOutcome signed =
              signRawNote(db.getDesign().getXPage(name), "XPage", name, dxl, comments);
          dropOrphanXspClasses(db.getDesign().getXPage(name));
          trace.accept(signed);
        }
      }
      case "wiringproperties" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(
                db.getDesign().getWiringPropertiesElement(name), "WiringProperties", name, dxl, comments));
      }
      case "compositeapplication" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(
                db.getDesign().getCompositeApplication(name), "CompositeApplication", name, dxl, comments));
      }
      case "compositecomponent" -> {
        name = DesignDxl.requireDesignName(dxl, "note", "fileresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(
                db.getDesign().getCompositeComponent(name), "CompositeComponent", name, dxl, comments));
      }
      case "dataconnection" -> {
        String requestedDc =
            DesignDxl.requireDesignName(
                dxl, "dataconnection", "note", "fileresource");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
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
          trace.accept(safeSign(elOpt.get(), dxl, comments));
        } else {
          trace.accept(signStampedDocument(entry.document(), "dataconnection", resolvedName, dxl, comments));
        }
      }
      case "navigator" -> {
        name = DesignDxl.requireDesignName(dxl, "navigator", "note");
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
        trace.accept(signNamed(db.getDesign().getNavigator(name), "Navigator", name, dxl, comments));
      }
      case "jar", "applet", "javasource", "webcontent", "xspproperties", "xpagesresource", "designnote" -> {
        String requested =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "navigator", "webservice", "agent", "appletresource");
        if ("webcontent".equals(t)) {
          WebContent.requireOutsideWebInf(requested);
        }
        DxlDesignPatch.importDesign(importer, dxl, db, mode);
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
          trace.accept(safeSign(elOpt.get(), dxl, comments));
        } else {
          // Residual notes may not map to a JNX DesignElement; sign the document directly
          trace.accept(signStampedDocument(entry.document(), bulkType, resolvedName, dxl, comments));
        }
      }
      default -> throw new KeepExceptionWrongDataRequest("Unsupported type: " + t);
    }

    JsonObject result = new JsonObject().put("success", true).put("type", t);
    if (name != null) {
      result.put("name", name);
    }
    if (trace.signWarning != null && !trace.signWarning.isBlank()) {
      result.put("signWarning", trace.signWarning);
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
   * A compiled XPage whose {@code $ClassData0} did not survive import makes HTTP look up that item
   * and answer 404. The page source is still in {@code $FileData}. Dropping the orphan class items
   * lets the XPages runtime compile the page after the design is signed.
   */
  private static void dropOrphanXspClasses(Optional<? extends DesignElement> opt)
      throws KeepExceptionServer {
    if (opt.isEmpty()) {
      return;
    }
    try {
      Document doc = opt.get().getDocument();
      if (doc.hasItem("$ClassData0")) {
        return;
      }
      boolean dropped = false;
      if (doc.hasItem("$ClassIndexItem")) {
        doc.removeItem("$ClassIndexItem");
        dropped = true;
      }
      for (int i = 0; i < 8; i++) {
        for (String prefix : new String[] {"$ClassData", "$ClassSize"}) {
          String item = prefix + i;
          if (doc.hasItem(item)) {
            doc.removeItem(item);
            dropped = true;
          }
        }
      }
      if (!dropped) {
        return;
      }
      doc.sign();
      doc.save();
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        return;
      }
      throw new KeepExceptionServer("XPages class cleanup failed: %s", message);
    }
  }

  /** Save without signing. Used when signing would replace a Java library with an empty jar. */
  private static SignOutcome saveRawNote(
      Optional<? extends DesignElement> opt,
      String label,
      String name,
      String dxl,
      CommentUpdate comments)
      throws KeepExceptionServer {
    return writeRawNote(opt, label, name, dxl, comments, false);
  }

  /**
   * Sign the note itself. Design-element save drops non-LotusScript library source.
   */
  private static SignOutcome signRawNote(
      Optional<? extends DesignElement> opt,
      String label,
      String name,
      String dxl,
      CommentUpdate comments)
      throws KeepExceptionServer {
    return writeRawNote(opt, label, name, dxl, comments, true);
  }

  private static SignOutcome writeRawNote(
      Optional<? extends DesignElement> opt,
      String label,
      String name,
      String dxl,
      CommentUpdate comments,
      boolean sign)
      throws KeepExceptionServer {
    DesignElement el =
        opt.orElseThrow(
            () ->
                new KeepExceptionServer(
                    "%s %s missing after DXL import; cannot sign", label, name));
    try {
      Document doc = el.getDocument();
      comments.apply(doc, dxl);
      if (sign) {
        doc.sign();
      }
      doc.save();
      return SignOutcome.signed();
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        return SignOutcome.failed(message);
      }
      throw new KeepExceptionServer("Sign failed: %s", message);
    }
  }

  private static SignOutcome signNamed(
      Optional<? extends DesignElement> opt,
      String label,
      String name,
      String dxl,
      CommentUpdate comments)
      throws KeepExceptionServer {
    DesignElement el =
        opt.orElseThrow(
            () ->
                new KeepExceptionServer(
                    "%s %s missing after DXL import; cannot sign", label, name));
    return safeSign(el, dxl, comments);
  }

  /**
   * Sign + save. Soft-fails on inconsistent DXL signatures (0x2dc) so bulk import can continue.
   */
  private static SignOutcome signStampedDocument(
      Document doc, String type, String name, String dxl, CommentUpdate comments)
      throws KeepExceptionServer {
    try {
      comments.apply(doc, dxl);
      doc.sign();
      doc.save();
      return SignOutcome.signed();
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        try {
          doc.save();
        } catch (Exception ignored) {
          // imported DXL may still be usable unsigned
        }
        return SignOutcome.failed(message);
      }
      throw new KeepExceptionServer("%s %s sign failed: %s", type, name, message);
    }
  }

  private static SignOutcome safeSign(DesignElement el, String dxl, CommentUpdate comments)
      throws KeepExceptionServer {
    try {
      comments.apply(el, dxl);
      el.sign();
      el.save();
      return SignOutcome.signed();
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("signature") || lower.contains("0x2dc") || lower.contains("2dc")) {
        try {
          el.save();
        } catch (Exception ignored) {
          // imported DXL may still be usable unsigned
        }
        return SignOutcome.failed(message);
      }
      throw new KeepExceptionServer("Sign failed: %s", message);
    }
  }

  private record SignOutcome(String warning) {
    private static SignOutcome signed() {
      return new SignOutcome(null);
    }

    private static SignOutcome failed(String warning) {
      return new SignOutcome(warning);
    }
  }

  private static final class WriteTrace {
    private String signWarning;

    private void accept(SignOutcome outcome) {
      if (outcome == null) {
        return;
      }
      if (outcome.warning() != null && !outcome.warning().isBlank()) {
        signWarning = outcome.warning();
      }
    }

    private void warningOnly(String warning) {
      signWarning = warning;
    }
  }
}
