package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DocumentClass;
import com.hcl.domino.data.IDTable;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.misc.NotesConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Domino Web Service Providers ({@code $Flags} contains {@code {} }) and Consumers ({@code
 * $FlagsExt} contains {@code W}).
 *
 * <p>JNX has no {@code getWebServices()}; discovery scans {@link DocumentClass#ALLNONDATA}.
 * Consumers are special script libraries and are excluded from typed {@code /scriptlibraries}
 * listing/bulk so they appear under {@code /webserviceconsumers} instead.
 */
final class WebServices {

  /** NotesConstants.DESIGN_FLAG_WEBSERVICE */
  private static final char FLAG_PROVIDER = NotesConstants.DESIGN_FLAG_WEBSERVICE.charAt(0);
  /** NotesConstants.DESIGN_FLAGEXT_WEBSERVICELIB */
  private static final char FLAG_CONSUMER_EXT = NotesConstants.DESIGN_FLAGEXT_WEBSERVICELIB.charAt(0);

  enum Kind {
    PROVIDER,
    CONSUMER
  }

  private WebServices() {}

  record Entry(Kind kind, String name, Document document) {}

  static List<Entry> list(Database db, Kind kind) {
    List<Entry> out = new ArrayList<>();
    for (Document doc : designDocuments(db)) {
      if (kind == Kind.PROVIDER && isProvider(doc)) {
        String title = titleOf(doc);
        if (title != null) {
          out.add(new Entry(Kind.PROVIDER, title, doc));
        }
      } else if (kind == Kind.CONSUMER && isConsumer(doc)) {
        String title = titleOf(doc);
        if (title != null) {
          out.add(new Entry(Kind.CONSUMER, title, doc));
        }
      }
    }
    out.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
    return out;
  }

  static Optional<Entry> find(Database db, Kind kind, String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String wanted = name.trim().toLowerCase(Locale.ROOT);
    for (Entry e : list(db, kind)) {
      if (e.name().toLowerCase(Locale.ROOT).equals(wanted)) {
        return Optional.of(e);
      }
    }
    return Optional.empty();
  }

  static Entry require(Database db, Kind kind, String name) throws KeepExceptionWrongDataRequest {
    String label = kind == Kind.PROVIDER ? "WebServiceProvider" : "WebServiceConsumer";
    return find(db, kind, name)
        .orElseThrow(() -> new KeepExceptionWrongDataRequest(label + " not found: " + name));
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

  static boolean isProvider(Document doc) {
    String flags = itemText(doc, "$Flags");
    return flags != null && flags.indexOf(FLAG_PROVIDER) >= 0;
  }

  static boolean isConsumer(Document doc) {
    // Prefer FlagsExt W; do not treat providers as consumers even if Ext has other letters
    if (isProvider(doc)) {
      return false;
    }
    String flagsExt = itemText(doc, "$FlagsExt");
    return flagsExt != null && flagsExt.indexOf(FLAG_CONSUMER_EXT) >= 0;
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
          // skip unsupported notes
        }
      }
    } catch (Exception ignored) {
      // unavailable
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
      // missing
    }
    return null;
  }
}
