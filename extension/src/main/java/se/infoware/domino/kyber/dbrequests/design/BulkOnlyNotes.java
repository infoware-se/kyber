package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DocumentClass;
import com.hcl.domino.data.IDTable;
import com.hcl.domino.design.DesignElement;
import io.vertx.core.json.JsonArray;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Bulk-only / residual design notes not covered by typed CRUD exporters.
 *
 * <p>After Navigators and known flag/title types, remaining {@link DocumentClass#ALLNONDATA}
 * design notes with a usable identity are exported as {@code designnote}. Data documents are omitted.
 * Notes that DXLExporter cannot serialize (prolog-only) are skipped so bulk matches COM
 * {@code SelectAllDesignElements}.
 */
final class BulkOnlyNotes {

  /** NotesConstants.DESIGN_FLAG_WEBSERVICE */
  private static final char FLAG_WEBSERVICE = '{';
  /** NotesConstants.DESIGN_FLAG_CONNECTION_RESOURCE */
  private static final char FLAG_CONNECTION = 'k';
  /** NotesConstants.DESIGN_FLAG_JAVA_RESOURCE */
  private static final char FLAG_JAVA_RESOURCE = '@';

  private static final Set<String> XPAGES_PROJECT_FILES =
      Set.of(
          "plugin.xml",
          "meta-inf/manifest.mf",
          ".classpath",
          ".project",
          "build.properties",
          "faces-config.xml",
          "web.xml");

  private BulkOnlyNotes() {}

  record Entry(String type, String name, Document document) {}

  static List<Entry> list(Database db, Set<String> alreadyExportedUnids) {
    return list(db, alreadyExportedUnids, null);
  }

  static List<Entry> list(Database db, Set<String> alreadyExportedUnids, JsonArray skipped) {
    Set<String> seen =
        alreadyExportedUnids == null ? new HashSet<>() : new HashSet<>(alreadyExportedUnids);
    List<Entry> out = new ArrayList<>();

    for (Document doc : designDocuments(db, skipped)) {
      String unid = unidOf(doc);
      if (unid != null && seen.contains(unid)) {
        continue;
      }
      if (isHandledElsewhere(doc) || isDataDocument(doc)) {
        continue;
      }

      String title = titleOf(doc);
      if (title == null || title.isBlank()) {
        // Still round-trip untitled design notes (e.g. some formulas) via UNID key
        if (unid == null) {
          continue;
        }
        title = "unid:" + unid;
      }

      String flags = itemText(doc, "$Flags");
      if (flags == null) {
        flags = "";
      }
      String flagsExt = itemText(doc, "$FlagsExt");
      if (flagsExt == null) {
        flagsExt = "";
      }

      String type = classify(title, flags, flagsExt);
      if (unid != null) {
        seen.add(unid);
      }
      out.add(new Entry(type, title, doc));
    }

    out.sort(
        Comparator.comparing(Entry::type, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
    return out;
  }

  static Optional<Entry> find(Database db, String type, String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String wanted = name.trim().toLowerCase(Locale.ROOT);
    String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    for (Entry e : list(db, Set.of())) {
      if (!t.isEmpty() && !t.equalsIgnoreCase(e.type())) {
        continue;
      }
      if (e.name().equalsIgnoreCase(wanted)) {
        return Optional.of(e);
      }
    }
    // After import, classification may differ; match by name across all extras
    if (!t.isEmpty()) {
      for (Entry e : list(db, Set.of())) {
        if (e.name().equalsIgnoreCase(wanted)) {
          return Optional.of(e);
        }
      }
    }
    return Optional.empty();
  }

  static Optional<DesignElement> asDesignElement(Database db, Document doc) {
    String unid = unidOf(doc);
    if (unid == null || unid.isBlank()) {
      return Optional.empty();
    }
    return db.getDesign().getDesignElementByUNID(unid);
  }

  /**
   * Prefer specific bulk types; otherwise {@code designnote} catch-all so nothing titled is
   * dropped.
   */
  private static String classify(String title, String flags, String flagsExt) {
    String lower = title.toLowerCase(Locale.ROOT).replace('\\', '/');
    if (lower.startsWith("unid:")) {
      return "designnote";
    }
    if ("web-inf/xsp.properties".equals(lower)) {
      return "xspproperties";
    }
    if (flags.indexOf(FLAG_WEBSERVICE) >= 0) {
      // Providers are exported via WebServices CRUD / typed bulk; should already be skipped
      return "webservice";
    }
    if (flags.indexOf(FLAG_CONNECTION) >= 0) {
      return "dataconnection";
    }
    if (flags.indexOf(FLAG_JAVA_RESOURCE) >= 0) {
      // Resources > Applets (shared Java / applet resource). Code > Jars use "34567Cg~,".
      return "applet";
    }
    // Code > Jars (Designer "Import Jar"): $Flags like "34567Cg~,"
    if (lower.endsWith(".jar") && flags.indexOf(',') >= 0) {
      return "jar";
    }
    // Code > Java (class/interface/enum): $Flags like "34567Cg~["
    if (lower.endsWith(".java") && flags.indexOf('[') >= 0) {
      return "javasource";
    }
    if (WebContent.isWebContent(lower, flagsExt)) {
      return "webcontent";
    }
    if (lower.startsWith("web-inf/")
        || lower.startsWith(".settings/")
        || lower.startsWith("icons/")
        || lower.startsWith("local/")
        || lower.startsWith("lib/")
        || lower.endsWith(".properties")
        || lower.endsWith(".xsp-config")
        || XPAGES_PROJECT_FILES.contains(lower)) {
      return "xpagesresource";
    }
    return "designnote";
  }

  /** Data notes and agent run data are not design and are not exported. */
  private static boolean isDataDocument(Document doc) {
    try {
      Set<DocumentClass> classes = doc.getDocumentClass();
      if (classes == null) {
        return false;
      }
      return classes.contains(DocumentClass.DATA) || classes.contains(DocumentClass.DOCUMENT);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean isHandledElsewhere(Document doc) {
    if (CustomControls.isCustomControl(doc)) {
      return true;
    }
    if (WebServices.isProvider(doc) || WebServices.isConsumer(doc)) {
      return true;
    }
    String title = titleOf(doc);
    if (title == null) {
      return false;
    }
    String flags = itemText(doc, "$Flags");
    if (flags == null) {
      flags = "";
    }
    // XPages (*gK)
    return flags.indexOf('K') >= 0 && title.toLowerCase(Locale.ROOT).endsWith(".xsp");
  }

  private static List<Document> designDocuments(Database db, JsonArray skipped) {
    List<Document> docs = new ArrayList<>();
    try {
      IDTable ids = db.getAllNoteIds(EnumSet.of(DocumentClass.ALLNONDATA), false);
      if (ids == null || ids.isEmpty()) {
        return docs;
      }
      for (Integer rawId : ids) {
        if (rawId == null) {
          continue;
        }
        try {
          db.getDocumentById(rawId).ifPresent(docs::add);
        } catch (Exception failed) {
          DesignElementExport.recordSkip(skipped, "designnote", "noteId:" + rawId, failed);
        }
      }
    } catch (Exception failed) {
      DesignElementExport.recordSkip(skipped, "designnote", null, failed);
    }
    return docs;
  }

  private static String unidOf(Document doc) {
    try {
      String unid = doc.getUNID();
      return unid == null || unid.isBlank() ? null : unid;
    } catch (Exception e) {
      return null;
    }
  }

  private static String titleOf(Document doc) {
    String title = itemText(doc, "$TITLE");
    if (title == null || title.isBlank()) {
      title = itemText(doc, "$Title");
    }
    if (title == null || title.isBlank()) {
      return null;
    }
    int pipe = title.indexOf('|');
    return (pipe >= 0 ? title.substring(0, pipe) : title).trim();
  }

  private static String itemText(Document doc, String itemName) {
    try {
      List<?> values = doc.getItemValue(itemName);
      if (values != null && !values.isEmpty() && values.get(0) != null) {
        String text = values.get(0).toString().trim();
        return text.isEmpty() ? null : text;
      }
    } catch (Exception ignored) {
      // missing
    }
    return null;
  }
}
