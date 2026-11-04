package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured inventory from design DXL without returning the full document. Used by {@code GET
 * /fields} and {@code GET /columns}.
 */
final class DesignStructure {

  private static final Pattern FIELD =
      Pattern.compile("(?is)<field\\b([^>]*?)(?:/>|>(?:(?!</field>).)*?</field>)");
  private static final Pattern COLUMN =
      Pattern.compile("(?is)<column\\b([^>]*)>(.*?)</column>");
  private static final Pattern COLUMN_HEADER_TITLE =
      Pattern.compile("(?is)<columnheader\\b[^>]*\\btitle=(['\"])(.*?)\\1");
  private static final Pattern FORMULA_VALUE =
      Pattern.compile("(?is)<code\\b[^>]*\\bevent=(['\"])value\\1[^>]*>\\s*<formula>(.*?)</formula>");

  private DesignStructure() {}

  static JsonObject listFields(Database db, String type, String name) throws Exception {
    String t = normalizeFieldType(type);
    String n = requireName(name);
    String dxl = requireDxl(db, t, n);
    JsonArray fields = fieldsFromDxl(dxl);
    return new JsonObject()
        .put("type", t)
        .put("name", n)
        .put("fields", fields)
        .put("count", fields.size());
  }

  static JsonObject listColumns(Database db, String type, String name) throws Exception {
    String t = normalizeColumnType(type);
    String n = requireName(name);
    String dxl = requireDxl(db, t, n);
    JsonArray columns = columnsFromDxl(dxl);
    return new JsonObject()
        .put("type", t)
        .put("name", n)
        .put("columns", columns)
        .put("count", columns.size());
  }

  /** Package-visible for unit tests. */
  static JsonArray fieldsFromDxl(String dxl) {
    JsonArray out = new JsonArray();
    if (dxl == null || dxl.isBlank()) {
      return out;
    }
    Matcher m = FIELD.matcher(dxl);
    while (m.find()) {
      String attrs = m.group(1);
      String fieldName = attr(attrs, "name");
      if (fieldName == null || fieldName.isBlank()) {
        continue;
      }
      JsonObject row =
          new JsonObject()
              .put("name", fieldName)
              .put("type", attrOr(attrs, "type", "text"))
              .put("kind", attrOr(attrs, "kind", "editable"));
      String allowmultivalues = attr(attrs, "allowmultivalues");
      if (allowmultivalues != null) {
        row.put("allowMultiValues", truthy(allowmultivalues));
      }
      String secret = attr(attrs, "secret");
      if (secret != null) {
        row.put("secret", truthy(secret));
      }
      out.add(row);
    }
    return out;
  }

  /** Package-visible for unit tests. */
  static JsonArray columnsFromDxl(String dxl) {
    JsonArray out = new JsonArray();
    if (dxl == null || dxl.isBlank()) {
      return out;
    }
    Matcher m = COLUMN.matcher(dxl);
    while (m.find()) {
      String attrs = m.group(1);
      String body = m.group(2);
      JsonObject row = new JsonObject();
      String itemName = attr(attrs, "itemname");
      if (itemName != null) {
        row.put("itemName", itemName);
      }
      String title = columnTitle(body, attrs);
      if (title != null) {
        row.put("title", title);
      }
      String formula = columnFormula(body);
      if (formula != null) {
        row.put("formula", formula);
      }
      String width = attr(attrs, "width");
      if (width != null) {
        row.put("width", width);
      }
      putBoolAttr(row, attrs, "hidden", "hidden");
      putBoolAttr(row, attrs, "categorized", "categorized");
      putBoolAttr(row, attrs, "sort", "sort");
      putBoolAttr(row, attrs, "sortdescending", "sortDescending");
      putBoolAttr(row, attrs, "resizable", "resizable");
      putBoolAttr(row, attrs, "separatemultiplevalues", "separateMultipleValues");
      putBoolAttr(row, attrs, "showaslinks", "showAsLinks");
      putBoolAttr(row, attrs, "hidedetailrows", "hideDetailRows");
      if (!row.isEmpty()) {
        out.add(row);
      }
    }
    return out;
  }

  private static String requireDxl(Database db, String type, String name) throws Exception {
    return DesignElementLookup.exportDxl(db, type, name)
        .orElseThrow(
            () ->
                new KeepExceptionNotFound(
                    DesignElementLookup.typeLabel(type) + " not found: " + name));
  }

  private static String requireName(String name) throws KeepExceptionWrongDataRequest {
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name parameter is required");
    }
    return name.trim();
  }

  private static String normalizeFieldType(String type) throws KeepExceptionWrongDataRequest {
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required (form|subform)");
    }
    String t = DesignBulkTypes.normalize(type);
    if (!"form".equals(t) && !"subform".equals(t)) {
      throw new KeepExceptionWrongDataRequest("type must be form or subform, got: " + type);
    }
    return t;
  }

  private static String normalizeColumnType(String type) throws KeepExceptionWrongDataRequest {
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required (view|folder)");
    }
    String t = DesignBulkTypes.normalize(type);
    if (!"view".equals(t) && !"folder".equals(t)) {
      throw new KeepExceptionWrongDataRequest("type must be view or folder, got: " + type);
    }
    return t;
  }

  private static String columnTitle(String body, String attrs) {
    Matcher header = COLUMN_HEADER_TITLE.matcher(body == null ? "" : body);
    if (header.find()) {
      return header.group(2);
    }
    return attr(attrs, "title");
  }

  private static String columnFormula(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    Matcher m = FORMULA_VALUE.matcher(body);
    if (!m.find()) {
      return null;
    }
    return m.group(2).replaceAll("\\s+", " ").trim();
  }

  private static void putBoolAttr(JsonObject row, String attrs, String xmlName, String jsonName) {
    String v = attr(attrs, xmlName);
    if (v != null) {
      row.put(jsonName, truthy(v));
    }
  }

  private static String attr(String attrs, String name) {
    if (attrs == null) {
      return null;
    }
    Matcher m =
        Pattern.compile("(?i)\\b" + Pattern.quote(name) + "=(['\"])(.*?)\\1").matcher(attrs);
    return m.find() ? m.group(2) : null;
  }

  private static String attrOr(String attrs, String name, String fallback) {
    String v = attr(attrs, name);
    return v == null || v.isBlank() ? fallback : v;
  }

  private static boolean truthy(String v) {
    String s = v.trim().toLowerCase(Locale.ROOT);
    return "true".equals(s) || "1".equals(s) || "yes".equals(s);
  }
}
