package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.AboutDocument;
import com.hcl.domino.design.DatabaseScriptLibrary;
import com.hcl.domino.design.DbProperties;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.SharedActions;
import com.hcl.domino.design.UsingDocument;
import com.hcl.domino.dxl.DxlExporter;
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
 * Export of design notes as CRUD-identical DXL.
 *
 * <p>Data documents and agent run data ({@code document}, {@code agentdata}) are not included.
 * Import of the exported notes is {@link DesignElementImport}.
 */
final class DesignElementExport {

  private DesignElementExport() {}

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
    elements.add(exportedElement(type, null, dxl.get()));
  }

  /** One exported note. {@code noteId} is the hex Domino writes in the DXL, omitted when absent. */
  static JsonObject exportedElement(String type, String name, String dxl) {
    JsonObject row = new JsonObject().put("type", type);
    if (name != null && !name.isBlank()) {
      row.put("name", name);
    }
    String noteId = NoteIds.fromDxl(dxl);
    if (noteId != null) {
      row.put("noteId", noteId);
    }
    return row.put("dxl", dxl);
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
          exportedElement(
              "sharedactions",
              SharedActionsNotes.nameOf(el),
              exporter.exportDocument(el.getDocument())));
    }
    for (SharedActions el : unnamed) {
      trackUnid(exportedUnids, el.getDocument());
      elements.add(
          exportedElement("sharedactions", null, exporter.exportDocument(el.getDocument())));
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
      String dxl = exporter.exportDocument(el.getDocument());
      if (DesignDxl.designName(dxl) == null) {
        continue;
      }
      trackUnid(exportedUnids, el.getDocument());
      elements.add(exportedElement(type, el.getTitle(), dxl));
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
        if (DesignDxl.designName(dxl) == null) {
          continue;
        }
        trackUnid(exportedUnids, doc);
        elements.add(exportedElement(type, el.getTitle(), dxl));
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
          exportedElement(
              "customcontrol", entry.name(), exporter.exportDocument(entry.document())));
    }
  }

  private static void exportWebServices(
      JsonArray elements, Database db, DxlExporter exporter, Set<String> exportedUnids)
      throws Exception {
    for (WebServices.Entry entry : WebServices.list(db, WebServices.Kind.PROVIDER)) {
      trackUnid(exportedUnids, entry.document());
      elements.add(
          exportedElement("webservice", entry.name(), exporter.exportDocument(entry.document())));
    }
    for (WebServices.Entry entry : WebServices.list(db, WebServices.Kind.CONSUMER)) {
      trackUnid(exportedUnids, entry.document());
      elements.add(
          exportedElement(
              "webserviceconsumer", entry.name(), exporter.exportDocument(entry.document())));
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
          exportedElement(
              "scriptlibrary", el.getTitle(), exporter.exportDocument(el.getDocument())));
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
        JsonObject roundTrip = residualRoundTrip(entry.type(), entry.name(), dxl);
        if (roundTrip == null) {
          continue;
        }
        trackUnid(exportedUnids, entry.document());
        String noteId = NoteIds.fromDxl(dxl);
        if (noteId != null) {
          roundTrip.put("noteId", noteId);
        }
        roundTrip.put("dxl", dxl);
        elements.add(roundTrip);
      } catch (Exception failed) {
        recordSkip(skipped, entry.type(), entry.name(), failed);
      }
    }
  }

  /**
   * Residual note to put in a round trip. Data documents ({@code document}, {@code agentdata}) are
   * never included. Untitled design with no name stays out. A named note whose DXL root is a real
   * design element is emitted as that type.
   *
   * @return element without {@code dxl}, or null when the note must not be exported
   */
  static JsonObject residualRoundTrip(String classifiedType, String fallbackName, String dxl) {
    String root = DesignDxl.rootLocalName(dxl);
    if (root == null || isDataDxlRoot(root) || DesignDxl.designName(dxl) == null) {
      return null;
    }
    String type = classifiedType == null ? "" : classifiedType.trim().toLowerCase(Locale.ROOT);
    if ("designnote".equals(type) && !designNoteRoot(root)) {
      String typed = apiTypeForRoot(root);
      String named = peekDesignName(dxl);
      if (typed == null || named == null) {
        return null;
      }
      return new JsonObject().put("type", typed).put("name", named);
    }
    String name = fallbackName == null ? "" : fallbackName.trim();
    if (name.isEmpty()) {
      return null;
    }
    return new JsonObject().put("type", type).put("name", name);
  }

  private static boolean isDataDxlRoot(String root) {
    return switch (root.toLowerCase(Locale.ROOT)) {
      case "document", "agentdata" -> true;
      default -> false;
    };
  }

  private static boolean designNoteRoot(String root) {
    return switch (root.toLowerCase(Locale.ROOT)) {
      case "note", "fileresource", "navigator", "webservice", "agent", "appletresource" -> true;
      default -> false;
    };
  }

  private static String apiTypeForRoot(String root) {
    return switch (root.toLowerCase(Locale.ROOT)) {
      case "view" -> "view";
      case "folder" -> "folder";
      case "form" -> "form";
      case "page" -> "page";
      case "subform" -> "subform";
      case "outline" -> "outline";
      case "frameset" -> "frameset";
      case "agent" -> "agent";
      case "scriptlibrary" -> "scriptlibrary";
      case "imageresource" -> "image";
      case "sharedfield" -> "sharedfield";
      case "sharedcolumn" -> "sharedcolumn";
      default -> null;
    };
  }

  private static String peekDesignName(String dxl) {
    java.util.regex.Matcher name =
        java.util.regex.Pattern.compile("(?is)<[A-Za-z][A-Za-z0-9_]*\\b[^>]*\\bname\\s*=\\s*['\"]([^'\"]+)['\"]")
            .matcher(dxl);
    if (!name.find()) {
      return null;
    }
    String value = name.group(1).trim();
    return value.isEmpty() ? null : value;
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
    return hasTag(lower, "note")
        || hasTag(lower, "database")
        || hasTag(lower, "form")
        || hasTag(lower, "view")
        || hasTag(lower, "agent")
        || hasTag(lower, "page")
        || hasTag(lower, "subform")
        || hasTag(lower, "outline")
        || hasTag(lower, "frameset")
        || hasTag(lower, "folder")
        || hasTag(lower, "imageresource")
        || hasTag(lower, "scriptlibrary")
        || hasTag(lower, "sharedactions")
        || hasTag(lower, "sharedfield")
        || hasTag(lower, "fileresource");
  }

  /** {@code <agent} must not match {@code <agentdata}. */
  private static boolean hasTag(String lower, String tag) {
    return java.util.regex.Pattern.compile("<" + tag + "\\b").matcher(lower).find();
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
}
