package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compare two design DXL documents into structured changes (fields/columns) plus line hunks. Used
 * by {@code GET|POST /diff}.
 */
final class DesignDiff {

  private static final int MAX_LINES = 2000;
  private static final int HUNK_CONTEXT = 2;
  private static final Pattern VOLATILE_ATTR =
      Pattern.compile("(?i)\\s+(?:noteid|unid|replicaid)=(['\"])[^'\"]*\\1");
  private static final Pattern DESIGNER_BUILD =
      Pattern.compile("(?is)\\s*<item\\s+name=(['\"])\\$DesignerBuild\\1[^>]*>.*?</item>");

  private DesignDiff() {}

  static JsonObject diff(Database db, JsonObject incoming) throws Exception {
    JsonObject in = unwrapBody(incoming);
    Side left = resolveSide(db, in, "left", true);
    Side right = resolveSide(db, in, "right", false);
    return compare(left, right);
  }

  /** KEEP may nest JSON under {@code body}. */
  static JsonObject unwrapBody(JsonObject incoming) {
    if (incoming == null) {
      return new JsonObject();
    }
    JsonObject body = incoming.getJsonObject("body");
    if (body == null) {
      return incoming;
    }
    JsonObject merged = incoming.copy();
    for (String key : body.fieldNames()) {
      if (!merged.containsKey(key)) {
        merged.put(key, body.getValue(key));
      }
    }
    return merged;
  }

  /** GET /diff?type=&name=&toName= (&toType= optional). */
  static JsonObject diffNamed(
      Database db, String type, String name, String toType, String toName) throws Exception {
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    if (name == null || name.isBlank() || toName == null || toName.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name and toName are required");
    }
    String rightType = toType == null || toType.isBlank() ? type : toType;
    Side left = loadNamed(db, type, name);
    Side right = loadNamed(db, rightType, toName);
    return compare(left, right);
  }

  /** Package-visible for unit tests. */
  static JsonObject compare(Side left, Side right) {
    String leftNorm = normalizeForDiff(left.dxl);
    String rightNorm = normalizeForDiff(right.dxl);
    JsonObject out =
        new JsonObject()
            .put("identical", leftNorm.equals(rightNorm))
            .put("left", sideMeta(left))
            .put("right", sideMeta(right));
    JsonArray changes = new JsonArray();

    String leftType = left.type == null ? "" : DesignBulkTypes.normalize(left.type);
    String rightType = right.type == null ? "" : DesignBulkTypes.normalize(right.type);
    if (leftType.equals(rightType)
        && ("form".equals(leftType) || "subform".equals(leftType))) {
      appendMapChanges(
          changes,
          "fields",
          "name",
          indexBy(DesignStructure.fieldsFromDxl(left.dxl), "name"),
          indexBy(DesignStructure.fieldsFromDxl(right.dxl), "name"));
    }
    if (leftType.equals(rightType)
        && ("view".equals(leftType) || "folder".equals(leftType))) {
      appendMapChanges(
          changes,
          "columns",
          "itemName",
          indexBy(DesignStructure.columnsFromDxl(left.dxl), "itemName"),
          indexBy(DesignStructure.columnsFromDxl(right.dxl), "itemName"));
    }

    String leftComment = DesignPayload.commentText(left.dxl);
    String rightComment = DesignPayload.commentText(right.dxl);
    if (!eq(leftComment, rightComment)) {
      changes.add(
          new JsonObject()
              .put("path", "comment")
              .put("op", op(leftComment, rightComment))
              .put("left", leftComment)
              .put("right", rightComment));
    }

    if (!leftNorm.equals(rightNorm)) {
      JsonObject dxlChange =
          new JsonObject()
              .put("path", "dxl")
              .put("op", "replace")
              .put("leftLines", lineCount(leftNorm))
              .put("rightLines", lineCount(rightNorm));
      JsonArray hunks = lineHunks(leftNorm, rightNorm);
      if (hunks != null) {
        dxlChange.put("hunks", hunks);
      } else {
        dxlChange.put("truncated", true);
      }
      changes.add(dxlChange);
    }

    out.put("changes", changes);
    out.put("changeCount", changes.size());
    return out;
  }

  static String normalizeForDiff(String dxl) {
    if (dxl == null) {
      return "";
    }
    String s = VOLATILE_ATTR.matcher(dxl).replaceAll("");
    s = DESIGNER_BUILD.matcher(s).replaceAll("");
    s = s.replace("\r\n", "\n").replace('\r', '\n');
    return s.trim();
  }

  /** Myers line diff hunks, or null when input is too large. */
  static JsonArray lineHunks(String left, String right) {
    List<String> a = lines(left);
    List<String> b = lines(right);
    if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
      return null;
    }
    int[][] lcs = new int[a.size() + 1][b.size() + 1];
    for (int i = a.size() - 1; i >= 0; i--) {
      for (int j = b.size() - 1; j >= 0; j--) {
        if (a.get(i).equals(b.get(j))) {
          lcs[i][j] = lcs[i + 1][j + 1] + 1;
        } else {
          lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        }
      }
    }
    List<String> ops = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      if (a.get(i).equals(b.get(j))) {
        ops.add(" ");
        texts.add(a.get(i));
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        ops.add("-");
        texts.add(a.get(i));
        i++;
      } else {
        ops.add("+");
        texts.add(b.get(j));
        j++;
      }
    }
    while (i < a.size()) {
      ops.add("-");
      texts.add(a.get(i++));
    }
    while (j < b.size()) {
      ops.add("+");
      texts.add(b.get(j++));
    }
    return packHunks(ops, texts);
  }

  private static JsonArray packHunks(List<String> ops, List<String> texts) {
    JsonArray hunks = new JsonArray();
    int n = ops.size();
    int idx = 0;
    while (idx < n) {
      while (idx < n && " ".equals(ops.get(idx))) {
        idx++;
      }
      if (idx >= n) {
        break;
      }
      int changeStart = idx;
      while (idx < n && !" ".equals(ops.get(idx))) {
        idx++;
      }
      int changeEnd = idx;
      int from = Math.max(0, changeStart - HUNK_CONTEXT);
      int to = Math.min(n, changeEnd + HUNK_CONTEXT);
      JsonArray lines = new JsonArray();
      for (int k = from; k < to; k++) {
        lines.add(new JsonObject().put("op", ops.get(k).trim().isEmpty() ? " " : ops.get(k)).put("text", texts.get(k)));
      }
      hunks.add(
          new JsonObject()
              .put("start", from + 1)
              .put("end", to)
              .put("lines", lines));
    }
    return hunks;
  }

  private static Side resolveSide(Database db, JsonObject incoming, String key, boolean required)
      throws Exception {
    JsonObject side = incoming.getJsonObject(key);
    if (side == null) {
      // Flat POST: left via type/name, right via dxl or toName
      if ("left".equals(key)) {
        String type = incoming.getString("type");
        String name = RequestParams.optionalName(incoming);
        if (type != null && name != null) {
          return loadNamed(db, type, name);
        }
      }
      if ("right".equals(key)) {
        String dxl = incoming.getString("dxl");
        if (dxl != null) {
          return sideFromDxl(dxl, incoming.getString("type"), null);
        }
        String toName = incoming.getString("toName");
        if (toName != null && !toName.isBlank()) {
          String type = incoming.getString("toType");
          if (type == null || type.isBlank()) {
            type = incoming.getString("type");
          }
          return loadNamed(db, type, toName);
        }
      }
      if (required) {
        throw new KeepExceptionWrongDataRequest(key + " is required");
      }
      throw new KeepExceptionWrongDataRequest(key + " is required");
    }
    String dxl = side.getString("dxl");
    if (dxl != null) {
      return sideFromDxl(dxl, side.getString("type"), side.getString("name"));
    }
    String type = side.getString("type");
    String name = side.getString("name");
    if (type == null || name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest(key + " needs type+name or dxl");
    }
    return loadNamed(db, type, name);
  }

  private static Side loadNamed(Database db, String type, String name) throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (!DesignBulkTypes.isKnown(t) || "acl".equals(t)) {
      throw new KeepExceptionWrongDataRequest("Unsupported type: " + type);
    }
    String n = name.trim();
    String dxl =
        DesignElementLookup.exportDxl(db, t, n)
            .orElseThrow(
                () ->
                    new KeepExceptionNotFound(
                        DesignElementLookup.typeLabel(t) + " not found: " + n));
    return new Side(t, n, dxl, "note");
  }

  private static Side sideFromDxl(String dxl, String typeHint, String nameHint) {
    String type = typeHint;
    if (type == null || type.isBlank()) {
      String root = DesignDxl.rootLocalName(dxl);
      type = root == null ? "designnote" : root.toLowerCase(Locale.ROOT);
      // map common roots
      type =
          switch (type) {
            case "imageresource" -> "image";
            case "scriptlibrary" -> "scriptlibrary";
            case "sharedfield" -> "sharedfield";
            case "sharedcolumn" -> "sharedcolumn";
            case "fileresource" -> "fileresource";
            case "appletresource" -> "applet";
            default -> type;
          };
    } else {
      type = DesignBulkTypes.normalize(type);
    }
    String name = nameHint;
    if (name == null || name.isBlank()) {
      name = DesignDxl.designName(dxl);
    }
    return new Side(type, name, dxl, "dxl");
  }

  private static JsonObject sideMeta(Side side) {
    JsonObject out = new JsonObject().put("source", side.source);
    if (side.type != null) {
      out.put("type", side.type);
    }
    if (side.name != null) {
      out.put("name", side.name);
    }
    return out;
  }

  private static Map<String, JsonObject> indexBy(JsonArray rows, String key) {
    Map<String, JsonObject> out = new LinkedHashMap<>();
    if (rows == null) {
      return out;
    }
    for (int i = 0; i < rows.size(); i++) {
      JsonObject row = rows.getJsonObject(i);
      String k = row.getString(key);
      if (k == null || k.isBlank()) {
        k = "#" + i;
      }
      out.put(k, row);
    }
    return out;
  }

  private static void appendMapChanges(
      JsonArray changes,
      String path,
      String keyName,
      Map<String, JsonObject> left,
      Map<String, JsonObject> right) {
    Map<String, Boolean> seen = new HashMap<>();
    for (Map.Entry<String, JsonObject> e : left.entrySet()) {
      seen.put(e.getKey(), true);
      JsonObject r = right.get(e.getKey());
      if (r == null) {
        changes.add(
            new JsonObject()
                .put("path", path)
                .put("op", "remove")
                .put(keyName, e.getKey())
                .put("left", e.getValue()));
      } else if (!e.getValue().encode().equals(r.encode())) {
        changes.add(
            new JsonObject()
                .put("path", path)
                .put("op", "replace")
                .put(keyName, e.getKey())
                .put("left", e.getValue())
                .put("right", r));
      }
    }
    for (Map.Entry<String, JsonObject> e : right.entrySet()) {
      if (seen.containsKey(e.getKey())) {
        continue;
      }
      changes.add(
          new JsonObject()
              .put("path", path)
              .put("op", "add")
              .put(keyName, e.getKey())
              .put("right", e.getValue()));
    }
  }

  private static String op(String left, String right) {
    if (left == null || left.isBlank()) {
      return "add";
    }
    if (right == null || right.isBlank()) {
      return "remove";
    }
    return "replace";
  }

  private static boolean eq(String a, String b) {
    if (a == null || a.isBlank()) {
      return b == null || b.isBlank();
    }
    return a.equals(b);
  }

  private static List<String> lines(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    String[] parts = text.split("\n", -1);
    List<String> out = new ArrayList<>(parts.length);
    for (String p : parts) {
      out.add(p);
    }
    return out;
  }

  private static int lineCount(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int n = 1;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }

  static final class Side {
    final String type;
    final String name;
    final String dxl;
    final String source;

    Side(String type, String name, String dxl, String source) {
      this.type = type;
      this.name = name;
      this.dxl = dxl == null ? "" : dxl;
      this.source = source;
    }
  }
}
