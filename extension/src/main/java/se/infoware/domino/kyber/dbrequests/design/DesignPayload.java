package se.infoware.domino.kyber.dbrequests.design;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fields a caller can use without parsing DXL. {@code source} on a write response already means
 * where the DXL came from ({@code dxl} or {@code default}), so the readable text is {@code code}.
 */
final class DesignPayload {

  private static final Pattern ITEM_TEXT =
      Pattern.compile(
          "(?is)<item\\s+name=(['\"])\\$%s\\1[^>]*>\\s*<text>(.*?)</text>");
  private static final Pattern COMMENT_ATTR =
      Pattern.compile("(?i)\\bcomment=(['\"])(.*?)\\1");
  private static final Pattern FILE_DATA =
      Pattern.compile(
          "(?is)<item\\s+name=(['\"])\\$FileData\\1[^>]*>\\s*<rawitemdata\\b[^>]*>(.*?)</rawitemdata>");
  private static final int FILE_HEADER = 42;

  private DesignPayload() {}

  static JsonObject note(String name, String dxl) {
    JsonObject out = new JsonObject().put("dxl", dxl == null ? "" : dxl);
    if (name != null && !name.isBlank()) {
      out.put("name", name);
    }
    enrich(out, dxl);
    return out;
  }

  /** Adds fileName, kind and code when the DXL has them and the field is still empty. */
  static void enrich(JsonObject out, String dxl) {
    if (out == null || dxl == null || dxl.isBlank()) {
      return;
    }
    String fileName = itemText(dxl, "FileNames");
    putIfAbsent(out, "fileName", fileName);
    putIfAbsent(out, "kind", kindOf(dxl, fileName == null ? itemText(dxl, "TITLE") : fileName));
    putIfAbsent(out, "code", codeOf(dxl, fileName));
    putIfAbsent(out, "noteId", NoteIds.fromDxl(dxl));
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

  private static String codeOf(String dxl, String fileName) {
    String script = blocks(dxl, "lotusscript");
    if (script == null) {
      script = blocks(dxl, "javascript");
    }
    if (script == null) {
      script = blocks(dxl, "java");
    }
    if (script == null && (dxl.contains("<agent ") || dxl.contains("<agent>"))) {
      script = blocks(dxl, "formula");
    }
    if (script != null) {
      return script;
    }
    String file = textFile(dxl, fileName);
    if (file != null) {
      return file;
    }
    if (dxl.contains("<fileresource") || dxl.contains("<stylesheetresource")) {
      return fileElement(dxl, fileName);
    }
    return null;
  }

  private static String blocks(String dxl, String tag) {
    Matcher m =
        Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(dxl);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String text = unescape(stripCdata(m.group(1))).trim();
      if (text.isEmpty() || text.toLowerCase(Locale.ROOT).contains("<rawitemdata")) {
        continue;
      }
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(text);
    }
    return out.length() == 0 ? null : out.toString();
  }

  private static String textFile(String dxl, String fileName) {
    if (fileName != null && isBinaryName(fileName)) {
      return null;
    }
    Matcher raw = FILE_DATA.matcher(dxl);
    if (!raw.find()) {
      return null;
    }
    byte[] bytes;
    try {
      bytes = Base64.getMimeDecoder().decode(raw.group(2).replaceAll("\\s", ""));
    } catch (IllegalArgumentException ex) {
      return null;
    }
    if (bytes.length <= FILE_HEADER) {
      return null;
    }
    byte[] body = new byte[bytes.length - FILE_HEADER];
    System.arraycopy(bytes, FILE_HEADER, body, 0, body.length);
    if (!isText(body)) {
      return null;
    }
    String text = new String(body, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    return text.isEmpty() ? null : text;
  }

  /** File resources and style sheets keep the bytes in a {@code <filedata>} element. */
  private static String fileElement(String dxl, String fileName) {
    if (fileName != null && isBinaryName(fileName)) {
      return null;
    }
    Matcher element = Pattern.compile("(?is)<filedata\\b[^>]*>(.*?)</filedata>").matcher(dxl);
    if (!element.find()) {
      return null;
    }
    String inner = element.group(1).trim();
    if (inner.isEmpty()) {
      return null;
    }
    String compact = inner.replaceAll("\\s", "");
    if (compact.matches("[A-Za-z0-9+/=]+") && compact.length() % 4 == 0) {
      try {
        byte[] decoded = Base64.getDecoder().decode(compact);
        if (isText(decoded)) {
          String text = new String(decoded, StandardCharsets.UTF_8).replace("\u0000", "").trim();
          if (!text.isEmpty()) {
            return text;
          }
        }
      } catch (IllegalArgumentException ignored) {
        // The element holds plain text.
      }
    }
    return inner;
  }

  private static boolean isBinaryName(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    return lower.endsWith(".class")
        || lower.endsWith(".jar")
        || lower.endsWith(".gif")
        || lower.endsWith(".png")
        || lower.endsWith(".jpg")
        || lower.endsWith(".jpeg")
        || lower.endsWith(".ico")
        || lower.endsWith(".bmp")
        || lower.endsWith(".zip")
        || lower.endsWith(".dll");
  }

  private static boolean isText(byte[] body) {
    int odd = 0;
    int sample = Math.min(body.length, 512);
    for (int i = 0; i < sample; i++) {
      int b = body[i] & 0xFF;
      if (b == 0 || (b < 9) || (b > 13 && b < 32 && b != 27)) {
        odd++;
      }
    }
    return odd * 20 < sample;
  }

  private static String itemText(String dxl, String item) {
    Matcher m = Pattern.compile(ITEM_TEXT.pattern().formatted(item)).matcher(dxl);
    if (!m.find()) {
      return null;
    }
    String text = unescape(m.group(2)).trim();
    return text.isEmpty() ? null : text;
  }

  private static String stripCdata(String text) {
    String trimmed = text.trim();
    if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
      return trimmed.substring(9, trimmed.length() - 3);
    }
    return text;
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
