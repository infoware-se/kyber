package se.infoware.domino.kyber.dbrequests.design;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fields a caller can use without parsing DXL. The design itself stays in {@code dxl}. {@code
 * source} on a write response is where that DXL came from ({@code dxl} or {@code default}).
 */
final class DesignPayload {

  private static final Pattern ITEM_TEXT =
      Pattern.compile(
          "(?is)<item\\s+name=(['\"])\\$%s\\1[^>]*>\\s*<text>(.*?)</text>");
  private static final Pattern COMMENT_ATTR =
      Pattern.compile("(?i)\\bcomment=(['\"])(.*?)\\1");

  private DesignPayload() {}

  static JsonObject note(String name, String dxl) {
    JsonObject out = new JsonObject().put("dxl", dxl == null ? "" : dxl);
    if (name != null && !name.isBlank()) {
      out.put("name", name);
    }
    enrich(out, dxl);
    return out;
  }

  /** Adds fileName and kind when the DXL has them and the field is still empty. */
  static void enrich(JsonObject out, String dxl) {
    if (out == null || dxl == null || dxl.isBlank()) {
      return;
    }
    String fileName = itemText(dxl, "FileNames");
    putIfAbsent(out, "fileName", fileName);
    putIfAbsent(out, "kind", kindOf(dxl, fileName == null ? itemText(dxl, "TITLE") : fileName));
    putIfAbsent(out, "noteId", NoteIds.fromDxl(dxl));
    putIfAbsent(out, "unid", NoteIds.unidFromDxl(dxl));
    JsonArray aliases = aliasesOf(dxl, out.getString("name"));
    if (aliases != null && !out.containsKey("aliases")) {
      out.put("aliases", aliases);
    }
  }

  private static void putIfAbsent(JsonObject out, String key, String value) {
    if (value == null || value.isBlank() || out.containsKey(key)) {
      return;
    }
    out.put(key, value);
  }

  private static JsonArray aliasesOf(String dxl, String name) {
    JsonArray fromTitle = DesignListing.aliasesFromPiped(itemText(dxl, "TITLE"), name);
    JsonArray fromRoot = DesignListing.aliasesFromPiped(rootName(dxl), name);
    if (fromTitle == null) {
      return fromRoot;
    }
    if (fromRoot == null || fromTitle.size() >= fromRoot.size()) {
      return fromTitle;
    }
    return fromRoot;
  }

  private static String rootName(String dxl) {
    Matcher root = Pattern.compile("(?s)<([A-Za-z][\\w:.-]*)\\b([^>]*)>").matcher(dxl);
    if (!root.find()) {
      return null;
    }
    Matcher name = Pattern.compile("(?i)\\bname=(['\"])(.*?)\\1").matcher(root.group(2));
    return name.find() ? name.group(2) : null;
  }

  private static String kindOf(String dxl, String fileName) {
    String lower = dxl.toLowerCase(Locale.ROOT);
    if (lower.contains("<agent ") || lower.contains("<agent>")) {
      return AgentKinds.detect(dxl);
    }
    if (lower.contains("<scriptlibrary")) {
      return ScriptLibraryKinds.detect(dxl);
    }
    if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".java")) {
      return JavaSourceKinds.detect(dxl);
    }
    return null;
  }

  static String commentText(String dxl) {
    return commentOf(dxl);
  }

  private static String commentOf(String dxl) {
    String item = itemText(dxl, "Comment");
    if (item != null) {
      return item;
    }
    Matcher attr = COMMENT_ATTR.matcher(dxl);
    if (!attr.find()) {
      return null;
    }
    return unescape(attr.group(2));
  }

  private static String itemText(String dxl, String item) {
    Matcher m = Pattern.compile(ITEM_TEXT.pattern().formatted(item)).matcher(dxl);
    if (!m.find()) {
      return null;
    }
    String text = unescape(m.group(2)).trim();
    return text.isEmpty() ? null : text;
  }

  private static String unescape(String text) {
    if (text == null) {
      return null;
    }
    return text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
  }
}
