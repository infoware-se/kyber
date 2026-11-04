package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact inventory for one design note without returning full DXL (unless {@code includeDxl}).
 * Used by {@code GET /summary}.
 */
final class DesignSummary {

  private static final Pattern VIEW_FORM =
      Pattern.compile("(?i)\\b(?:form|usedform|defaultform)=(['\"])(.*?)\\1");
  private static final Pattern SCHEDULE_BLOCK =
      Pattern.compile("(?is)<schedule\\b([^>]*)(?:/>|>(.*?)</schedule>)");

  private DesignSummary() {}

  static JsonObject summarize(Database db, JsonObject incoming) throws Exception {
    boolean includeDxl = includeDxl(incoming);
    String unid = blankToNull(incoming.getString("unid"));
    String noteId = blankToNull(incoming.getString("noteId"));
    String type = blankToNull(incoming.getString("type"));
    String name = RequestParams.optionalName(incoming);

    if (unid != null || noteId != null) {
      if (type != null || name != null) {
        throw new KeepExceptionWrongDataRequest("pass unid/noteId or type+name, not both");
      }
      JsonObject looked = DesignResolve.lookup(db, unid, noteId, true);
      String dxl = looked.getString("dxl", "");
      JsonObject out =
          fromDxl(
              looked.getString("type"),
              looked.getString("name"),
              dxl,
              looked.getString("noteId"),
              looked.getString("unid"));
      if (includeDxl) {
        out.put("dxl", dxl);
      }
      return out;
    }

    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required when unid/noteId is omitted");
    }
    if (name == null) {
      throw new KeepExceptionWrongDataRequest("name is required when unid/noteId is omitted");
    }
    String t = DesignBulkTypes.normalize(type);
    if (!DesignBulkTypes.isKnown(t) || "acl".equals(t)) {
      throw new KeepExceptionWrongDataRequest("Unsupported type: " + type);
    }
    String dxl =
        DesignElementLookup.exportDxl(db, t, name)
            .orElseThrow(
                () ->
                    new KeepExceptionNotFound(
                        DesignElementLookup.typeLabel(t) + " not found: " + name));
    JsonObject out = fromDxl(t, name, dxl, null, null);
    if (includeDxl) {
      out.put("dxl", dxl);
    }
    return out;
  }

  /** Package-visible for unit tests. */
  static JsonObject fromDxl(String type, String name, String dxl, String noteId, String unid) {
    JsonObject out = new JsonObject();
    if (type != null && !type.isBlank()) {
      out.put("type", DesignBulkTypes.normalize(type));
    }
    if (name != null && !name.isBlank()) {
      out.put("name", name);
    }
    DesignPayload.enrich(out, dxl);
    if (noteId != null && !noteId.isBlank() && !out.containsKey("noteId")) {
      out.put("noteId", noteId);
    }
    if (unid != null && !unid.isBlank() && !out.containsKey("unid")) {
      out.put("unid", unid);
    }
    String comment = DesignPayload.commentText(dxl);
    if (comment != null && !comment.isBlank()) {
      out.put("comment", comment);
    }

    String t = out.getString("type", type == null ? "" : DesignBulkTypes.normalize(type));
    if ("agent".equals(t)) {
      String trigger = ListAgents.detectTrigger(dxl);
      if (trigger != null) {
        out.put("trigger", trigger);
      }
      JsonObject schedule = scheduleOf(dxl);
      if (schedule != null && !schedule.isEmpty()) {
        out.put("schedule", schedule);
      }
    }
    if ("form".equals(t) || "subform".equals(t)) {
      out.put("fieldCount", DesignStructure.fieldsFromDxl(dxl).size());
    }
    if ("view".equals(t) || "folder".equals(t)) {
      out.put("columnCount", DesignStructure.columnsFromDxl(dxl).size());
      JsonArray forms = formsUsed(dxl);
      if (forms != null && !forms.isEmpty()) {
        out.put("forms", forms);
      }
    }
    return out;
  }

  private static JsonArray formsUsed(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    JsonArray out = new JsonArray();
    Matcher m = VIEW_FORM.matcher(dxl);
    while (m.find()) {
      String form = m.group(2).trim();
      if (form.isEmpty()) {
        continue;
      }
      boolean seen = false;
      for (int i = 0; i < out.size(); i++) {
        if (form.equalsIgnoreCase(out.getString(i))) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        out.add(form);
      }
    }
    return out;
  }

  /** Package-visible for {@link DesignSchedules} and unit tests. */
  static JsonObject scheduleOf(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    Matcher m = SCHEDULE_BLOCK.matcher(dxl);
    if (!m.find()) {
      return null;
    }
    String attrs = m.group(1);
    JsonObject out = new JsonObject();
    putAttr(out, attrs, "enabled", "enabled");
    putAttr(out, attrs, "type", "type");
    putAttr(out, attrs, "runon", "runOn");
    putAttr(out, attrs, "starthour", "startHour");
    putAttr(out, attrs, "endhour", "endHour");
    putAttr(out, attrs, "date", "date");
    putAttr(out, attrs, "time", "time");
    putAttr(out, attrs, "days", "days");
    return out.isEmpty() ? null : out;
  }

  private static void putAttr(JsonObject out, String attrs, String xml, String json) {
    if (attrs == null) {
      return;
    }
    Matcher m =
        Pattern.compile("(?i)\\b" + Pattern.quote(xml) + "=(['\"])(.*?)\\1").matcher(attrs);
    if (m.find()) {
      out.put(json, m.group(2));
    }
  }

  private static boolean includeDxl(JsonObject incoming) {
    if (incoming == null || !incoming.containsKey("includeDxl")) {
      return false;
    }
    Object v = incoming.getValue("includeDxl");
    if (v instanceof Boolean b) {
      return b;
    }
    return "true".equalsIgnoreCase(String.valueOf(v));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
