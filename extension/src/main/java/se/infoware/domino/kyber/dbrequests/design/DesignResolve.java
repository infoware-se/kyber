package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolve design notes by UNID or noteId, and search across types by name or DXL substring.
 */
final class DesignResolve {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;

  private DesignResolve() {}

  static JsonObject lookup(
      Database db, String unid, String noteId, boolean includeDxl) throws Exception {
    if ((unid == null || unid.isBlank()) && (noteId == null || noteId.isBlank())) {
      throw new KeepExceptionWrongDataRequest("unid or noteId is required");
    }
    if (unid != null && !unid.isBlank() && noteId != null && !noteId.isBlank()) {
      throw new KeepExceptionWrongDataRequest("pass unid or noteId, not both");
    }
    Document doc;
    if (unid != null && !unid.isBlank()) {
      Optional<DesignElement> el = db.getDesign().getDesignElementByUNID(unid.trim());
      if (el.isPresent()) {
        doc = el.get().getDocument();
      } else {
        doc =
            db.getDocumentByUNID(unid.trim())
                .orElseThrow(() -> new KeepExceptionNotFound("Design note not found: " + unid));
      }
    } else {
      int id = parseNoteId(noteId.trim());
      doc =
          db.getDocumentById(id)
              .orElseThrow(() -> new KeepExceptionNotFound("Design note not found: " + noteId));
    }
    return toElement(db, doc, includeDxl);
  }

  static JsonObject search(
      Database db, String q, String typesCsv, String match, Integer limitParam) throws Exception {
    if (q == null || q.isBlank()) {
      throw new KeepExceptionWrongDataRequest("q is required");
    }
    String needle = q.trim().toLowerCase(Locale.ROOT);
    String mode = match == null || match.isBlank() ? "both" : match.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("name", "dxl", "both").contains(mode)) {
      throw new KeepExceptionWrongDataRequest("match must be name, dxl, or both");
    }
    int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
    if (limit < 1) {
      limit = 1;
    }
    if (limit > MAX_LIMIT) {
      limit = MAX_LIMIT;
    }
    Set<String> typeFilter = parseTypes(typesCsv);

    JsonArray elements = new JsonArray();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    // Prefer bulk residual scan + typed exportAll so names and types stay consistent with /export.
    JsonArray exported = DesignElementExport.exportAll(db);
    for (int i = 0; i < exported.size() && elements.size() < limit; i++) {
      JsonObject el = exported.getJsonObject(i);
      String type = DesignBulkTypes.normalize(el.getString("type", ""));
      if (!typeFilter.isEmpty() && !typeFilter.contains(type)) {
        continue;
      }
      String name = el.getString("name", "");
      String dxl = el.getString("dxl", "");
      boolean hit = false;
      if (("name".equals(mode) || "both".equals(mode))
          && name.toLowerCase(Locale.ROOT).contains(needle)) {
        hit = true;
      }
      if (!hit
          && ("dxl".equals(mode) || "both".equals(mode))
          && dxl.toLowerCase(Locale.ROOT).contains(needle)) {
        hit = true;
      }
      if (!hit) {
        continue;
      }
      JsonObject row =
          new JsonObject()
              .put("type", type)
              .put("name", name)
              .put("noteId", el.getString("noteId"))
              .put("unid", el.getString("unid"));
      elements.add(row);
    }
    // Touch exporter so callers with hollow notes still have a session handle (no-op use).
    if (exporter != null && elements.isEmpty()) {
      // nothing
    }
    return new JsonObject()
        .put("q", q.trim())
        .put("match", mode)
        .put("limit", limit)
        .put("count", elements.size())
        .put("elements", elements);
  }

  private static JsonObject toElement(Database db, Document doc, boolean includeDxl)
      throws Exception {
    String title = itemText(doc, "$TITLE");
    if (title == null) {
      title = itemText(doc, "$Title");
    }
    String name = title;
    if (name != null) {
      int pipe = name.indexOf('|');
      if (pipe >= 0) {
        name = name.substring(0, pipe).trim();
      } else {
        name = name.trim();
      }
    }
    String flags = itemText(doc, "$Flags");
    if (flags == null) {
      flags = "";
    }
    String flagsExt = itemText(doc, "$FlagsExt");
    if (flagsExt == null) {
      flagsExt = "";
    }
    if (name == null || name.isBlank()) {
      String unid = NoteIds.unidOf(doc);
      name = unid == null ? "unid:unknown" : "unid:" + unid;
    }
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(doc);
    String type = typeFromDxl(dxl);
    if (type == null) {
      type = classifyWithFallback(db, doc, name, flags, flagsExt);
    }
    String resolvedName = DesignDxl.designName(dxl);
    JsonObject out =
        new JsonObject()
            .put("type", type)
            .put("name", resolvedName != null ? resolvedName : name);
    String noteId = NoteIds.of(doc);
    if (noteId != null) {
      out.put("noteId", noteId);
    }
    String unid = NoteIds.unidOf(doc);
    if (unid != null) {
      out.put("unid", unid);
    }
    DesignPayload.enrich(out, dxl);
    if (includeDxl) {
      out.put("dxl", dxl);
    }
    return out;
  }

  private static String typeFromDxl(String dxl) {
    return apiTypeForRoot(DesignDxl.rootLocalName(dxl));
  }

  private static String classifyWithFallback(
      Database db, Document doc, String name, String flags, String flagsExt) {
    String unid = NoteIds.unidOf(doc);
    if (unid != null) {
      for (BulkOnlyNotes.Entry e : BulkOnlyNotes.list(db, Set.of())) {
        if (unid.equalsIgnoreCase(NoteIds.unidOf(e.document()))) {
          return e.type();
        }
      }
    }
    return "designnote";
  }

  private static String apiTypeForRoot(String root) {
    if (root == null) {
      return null;
    }
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
      case "fileresource" -> "fileresource";
      case "appletresource" -> "applet";
      case "dataconnection" -> "dataconnection";
      case "stylesheet" -> "stylesheet";
      case "theme" -> "theme";
      case "navigator" -> "navigator";
      default -> null;
    };
  }

  private static Set<String> parseTypes(String typesCsv) {
    if (typesCsv == null || typesCsv.isBlank()) {
      return Set.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : typesCsv.split("[,;\\s]+")) {
      if (part.isBlank()) {
        continue;
      }
      out.add(DesignBulkTypes.normalize(part));
    }
    return Set.copyOf(out);
  }

  private static int parseNoteId(String raw) throws KeepExceptionWrongDataRequest {
    String s = raw.trim();
    if (s.regionMatches(true, 0, "0x", 0, 2)) {
      s = s.substring(2);
    }
    if (s.regionMatches(true, 0, "NT", 0, 2)) {
      s = s.substring(2);
    }
    try {
      return Integer.parseUnsignedInt(s, 16);
    } catch (NumberFormatException e) {
      throw new KeepExceptionWrongDataRequest("invalid noteId: " + raw);
    }
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
