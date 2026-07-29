package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * CRUD helpers for design notes discovered via {@link BulkOnlyNotes} (dataconnection, jar, applet,
 * javasource).
 */
final class ResidualDesign {

  private ResidualDesign() {}

  static List<String> listNames(Database db, String type) {
    List<String> names = new ArrayList<>();
    for (BulkOnlyNotes.Entry e : BulkOnlyNotes.list(db, java.util.Set.of())) {
      if (type.equalsIgnoreCase(e.type())) {
        names.add(e.name());
      }
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }

  static BulkOnlyNotes.Entry require(Database db, String type, String name)
      throws KeepExceptionWrongDataRequest {
    return BulkOnlyNotes.find(db, type, name)
        .orElseThrow(
            () ->
                new KeepExceptionWrongDataRequest(
                    type + " not found: " + (name == null ? "" : name)));
  }

  static String exportDxl(Database db, Document doc) throws Exception {
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    return exporter.exportDocument(doc);
  }

  /**
   * Import DXL then sign the residual note. Returns importer log (may be empty).
   *
   * @return [resolvedName, log]
   */
  static String[] importAndSign(
      Database db, DxlImporter importer, String type, String dxl, String... rootHints)
      throws Exception {
    String requested = DesignDxl.requireDesignName(dxl, rootHints);
    String log = DxlDesignPatch.importDesign(importer, dxl, db);
    BulkOnlyNotes.Entry entry =
        BulkOnlyNotes.find(db, type, requested)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "%s %s missing after DXL import; cannot sign", type, requested));
    signEntry(db, type, entry);
    return new String[] {entry.name(), log == null ? "" : log};
  }

  static void signEntry(Database db, String type, BulkOnlyNotes.Entry entry)
      throws KeepExceptionServer {
    Optional<DesignElement> elOpt = BulkOnlyNotes.asDesignElement(db, entry.document());
    if (elOpt.isPresent()) {
      try {
        elOpt.get().sign();
        elOpt.get().save();
      } catch (Exception e) {
        softOrThrow(type, entry.name(), e);
      }
      return;
    }
    try {
      entry.document().sign();
    } catch (Exception e) {
      softOrThrow(type, entry.name(), e);
    }
  }

  static void delete(Database db, String type, String name) throws Exception {
    BulkOnlyNotes.Entry entry = require(db, type, name);
    Optional<DesignElement> elOpt = BulkOnlyNotes.asDesignElement(db, entry.document());
    if (elOpt.isPresent()) {
      elOpt.get().delete();
    } else {
      entry.document().delete();
    }
  }

  private static void softOrThrow(String type, String name, Exception e)
      throws KeepExceptionServer {
    String message = e.getMessage() == null ? e.toString() : e.getMessage();
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("signature")
        || lower.contains("0x2dc")
        || lower.contains("2dc")) {
      return;
    }
    throw new KeepExceptionServer("%s %s sign failed: %s", type, name, message);
  }
}
