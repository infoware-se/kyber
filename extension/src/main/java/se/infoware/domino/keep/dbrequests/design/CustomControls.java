package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DocumentClass;
import com.hcl.domino.data.IDTable;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * XPages Custom Controls are design notes with {@code $TITLE} ending in {@code .xsp} and {@code
 * $Flags} that do <em>not</em> contain {@code K} (the XPage marker used by JNX {@code *gK}).
 *
 * <p>JNX {@code getXPages()} only returns notes matching {@code *gK}; {@code
 * getCompositeComponents()} matches Eclipse CAE components ({@code (+-*g_}), not Designer Custom
 * Controls ({@code gC~4;}). Discovery therefore scans {@link DocumentClass#ALLNONDATA}.
 */
final class CustomControls {

  private CustomControls() {}

  record Entry(String name, Document document) {}

  static List<Entry> list(Database db) {
    List<Entry> out = new ArrayList<>();
    for (Document doc : designDocuments(db)) {
      if (isCustomControl(doc)) {
        String title = titleOf(doc);
        if (title != null) {
          out.add(new Entry(title, doc));
        }
      }
    }
    out.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
    return out;
  }

  static Optional<Entry> find(Database db, String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String wanted = name.trim();
    String wantedLower = wanted.toLowerCase(Locale.ROOT);
    String withXsp =
        wantedLower.endsWith(".xsp") ? wantedLower : wantedLower + ".xsp";

    Optional<Entry> exact = Optional.empty();
    Optional<Entry> xsp = Optional.empty();
    for (Entry e : list(db)) {
      String titleLower = e.name().toLowerCase(Locale.ROOT);
      if (titleLower.equals(wantedLower)) {
        exact = Optional.of(e);
        break;
      }
      if (xsp.isEmpty() && titleLower.equals(withXsp)) {
        xsp = Optional.of(e);
      }
    }
    return exact.isPresent() ? exact : xsp;
  }

  static Entry require(Database db, String name) throws KeepExceptionWrongDataRequest {
    return find(db, name)
        .orElseThrow(
            () -> new KeepExceptionWrongDataRequest("CustomControl not found: " + name));
  }

  static String exportDxl(Database db, Document doc) throws Exception {
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    return exporter.exportDocument(doc);
  }

  static Optional<DesignElement> asDesignElement(Database db, Document doc) {
    String unid = doc.getUNID();
    if (unid == null || unid.isBlank()) {
      return Optional.empty();
    }
    return db.getDesign().getDesignElementByUNID(unid);
  }

  static boolean isCustomControl(Document doc) {
    String title = titleOf(doc);
    if (title == null || !title.toLowerCase(Locale.ROOT).endsWith(".xsp")) {
      return false;
    }
    String flags = itemText(doc, "$Flags");
    if (flags == null) {
      flags = "";
    }
    // XPages match JNX pattern *gK; Custom Controls use gC~4; (no K).
    return flags.indexOf('K') < 0;
  }

  private static List<Document> designDocuments(Database db) {
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
        } catch (Exception ignored) {
          // skip unsupported / broken design notes
        }
      }
    } catch (Exception ignored) {
      // getAllNoteIds unavailable on some NSF builds
    }
    return docs;
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
      // missing item
    }
    return null;
  }
}
