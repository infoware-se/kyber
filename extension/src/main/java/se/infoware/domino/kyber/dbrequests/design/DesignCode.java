package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the readable {@code code} text back onto a note that has one body. A form stays DXL,
 * because its scripts belong to separate events.
 */
final class DesignCode {

  private static final Pattern FILE_DATA =
      Pattern.compile(
          "(?is)(<item\\s+name=(['\"])\\$FileData\\2[^>]*>\\s*<rawitemdata\\b[^>]*>)([A-Za-z0-9+/=\\s]+)(</rawitemdata>)");
  private static final Pattern PROCEDURE =
      Pattern.compile(
          "(?m)^[ \\t]*(?:(?:Public|Private)\\s+)?(?:Sub|Function|Class|Property)\\b");
  private static final int FILE_HEADER = 42;

  private DesignCode() {}

  static boolean accepts(String type) {
    return switch (type == null ? "" : type) {
      case "agent", "scriptlibrary", "javasource", "webcontent", "fileresource", "stylesheet",
          "xpagesresource" ->
          true;
      default -> false;
    };
  }

  /** Replace the script or text-file body. The rest of the DXL stays. */
  static String apply(String dxl, String code, String type) throws KeepExceptionWrongDataRequest {
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl body is required");
    }
    if (!accepts(type)) {
      throw new KeepExceptionWrongDataRequest(
          "code is accepted for agent, scriptlibrary, javasource, webcontent, fileresource, stylesheet, and xpagesresource");
    }
    String text = code == null ? "" : code;
    if ("javasource".equals(type)
        || "webcontent".equals(type)
        || "fileresource".equals(type)
        || "stylesheet".equals(type)
        || "xpagesresource".equals(type)) {
      return replaceFile(dxl, text, type);
    }
    String scripts = replaceScripts(dxl, text, "agent".equals(type));
    if (scripts != null) {
      return scripts;
    }
    try {
      return replaceFile(dxl, text, type);
    } catch (KeepExceptionWrongDataRequest ex) {
      throw new KeepExceptionWrongDataRequest("code cannot be written onto this note");
    }
  }

  private static String replaceScripts(String dxl, String code, boolean agent) {
    String updated = replaceTag(dxl, "lotusscript", code, true);
    if (updated != null) {
      return updated;
    }
    updated = replaceTag(dxl, "javascript", code, false);
    if (updated != null) {
      return updated;
    }
    updated = replaceTag(dxl, "java", code, false);
    if (updated != null) {
      return updated;
    }
    if (agent) {
      return replaceTag(dxl, "formula", code, false);
    }
    return null;
  }

  /**
   * LotusScript with an Options block and a later procedure keeps the preamble in the first block
   * and the procedure in the last. One block receives the whole text.
   */
  private static String replaceTag(String dxl, String tag, String code, boolean splitProcedures) {
    Matcher matcher =
        Pattern.compile("(?is)(<" + tag + "\\b[^>]*>)(.*?)(</" + tag + ">)").matcher(dxl);
    List<int[]> spans = new ArrayList<>();
    matcher.reset();
    while (matcher.find()) {
      if (matcher.group(2).toLowerCase(Locale.ROOT).contains("<rawitemdata")) {
        continue;
      }
      spans.add(
          new int[] {matcher.start(), matcher.end(), matcher.start(1), matcher.end(1), matcher.start(2), matcher.end(2), matcher.start(3), matcher.end(3)});
    }
    if (spans.isEmpty()) {
      return null;
    }
    boolean onlyLast = false;
    String preamble = code;
    String body = code;
    if (spans.size() > 1 && splitProcedures) {
      Matcher procedure = PROCEDURE.matcher(code);
      if (procedure.find()) {
        preamble = code.substring(0, procedure.start()).trim();
        body = code.substring(procedure.start()).trim();
      } else {
        onlyLast = true;
      }
    }
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    for (int i = 0; i < spans.size(); i++) {
      int[] span = spans.get(i);
      out.append(dxl, cursor, span[0]);
      if (onlyLast && i != spans.size() - 1) {
        out.append(dxl, span[0], span[1]);
        cursor = span[1];
        continue;
      }
      String inner;
      if (spans.size() == 1 || onlyLast) {
        inner = code;
      } else if (!splitProcedures) {
        inner = i == 0 ? code : "";
      } else if (i == 0) {
        inner = preamble;
      } else if (i == spans.size() - 1) {
        inner = body;
      } else {
        inner = "";
      }
      out.append(dxl, span[2], span[3]);
      out.append(xmlText(inner));
      out.append(dxl, span[6], span[7]);
      cursor = span[1];
    }
    out.append(dxl.substring(cursor));
    return out.toString();
  }

  private static String replaceFile(String dxl, String code, String type)
      throws KeepExceptionWrongDataRequest {
    String fileName = fileName(dxl);
    if (fileName != null && isBinaryName(fileName)) {
      throw new KeepExceptionWrongDataRequest("code is not written onto binary file " + fileName);
    }
    Matcher raw = FILE_DATA.matcher(dxl);
    if (!raw.find()) {
      String element = replaceFileElement(dxl, code);
      if (element != null) {
        return element;
      }
      throw new KeepExceptionWrongDataRequest("code cannot be written onto this note");
    }
    byte[] decoded;
    try {
      decoded = Base64.getMimeDecoder().decode(raw.group(3).replaceAll("\\s", ""));
    } catch (IllegalArgumentException ex) {
      throw new KeepExceptionWrongDataRequest("invalid file encoding");
    }
    if (decoded.length < FILE_HEADER) {
      throw new KeepExceptionWrongDataRequest("code cannot be written onto this note");
    }
    byte[] body = code.getBytes(StandardCharsets.UTF_8);
    int oldSize = decoded.length - FILE_HEADER;
    byte[] next = new byte[FILE_HEADER + body.length];
    System.arraycopy(decoded, 0, next, 0, FILE_HEADER);
    System.arraycopy(body, 0, next, FILE_HEADER, body.length);
    writeLe32(next, 8, body.length);
    patchFileSizes(decoded, next, oldSize, body.length);
    String encoded = Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(next);
    String out =
        dxl.substring(0, raw.start(3)) + "\n" + encoded + "\n" + dxl.substring(raw.end(3));
    out =
        out.replaceAll(
            "(?i)(<item\\s+name=(['\"])\\$FileSize\\2[^>]*>\\s*<number>)\\d+(</number>)",
            "$1" + body.length + "$3");
    if ("javasource".equals(type)) {
      out =
          out.replaceAll(
              "(?is)<item\\s+name=(['\"])\\$Class(?:Data0|Size0|IndexItem)\\1[^>]*>.*?</item>",
              "");
    }
    return out;
  }

  /**
   * The host file header stores the byte count as a DWORD at offset 8, a WORD at offset 26
   * (count + 18), and a WORD at offset 30. Offset 32 sometimes repeats the count. These are
   * not overlapping DWORDs; writing four bytes at 28 corrupts an empty file.
   */
  private static void patchFileSizes(byte[] raw, byte[] next, int oldSize, int newSize) {
    int at26 = readLe16(raw, 26);
    if (at26 == oldSize + 18) {
      writeLe16(next, 26, newSize + 18);
    } else if (oldSize != 0 && at26 == oldSize) {
      writeLe16(next, 26, newSize);
    }
    if (readLe16(raw, 30) == oldSize) {
      writeLe16(next, 30, newSize);
    }
    if (oldSize == 0) {
      return;
    }
    int at32 = readLe16(raw, 32);
    if (at32 == oldSize) {
      writeLe16(next, 32, newSize);
    } else if (at32 == oldSize + 1) {
      writeLe16(next, 32, newSize + 1);
    }
  }

  private static int readLe16(byte[] raw, int offset) {
    if (offset + 1 >= raw.length) {
      return -1;
    }
    return (raw[offset] & 0xff) | ((raw[offset + 1] & 0xff) << 8);
  }

  private static void writeLe16(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value & 0xff);
    raw[offset + 1] = (byte) ((value >>> 8) & 0xff);
  }

  private static void writeLe32(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value & 0xff);
    raw[offset + 1] = (byte) ((value >>> 8) & 0xff);
    raw[offset + 2] = (byte) ((value >>> 16) & 0xff);
    raw[offset + 3] = (byte) ((value >>> 24) & 0xff);
  }

  private static String replaceFileElement(String dxl, String code) {
    Matcher element = Pattern.compile("(?is)(<filedata\\b[^>]*>)(.*?)(</filedata>)").matcher(dxl);
    if (!element.find()) {
      return null;
    }
    String encoded =
        Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(code.getBytes(StandardCharsets.UTF_8));
    return dxl.substring(0, element.start(2)) + "\n" + encoded + "\n" + dxl.substring(element.end(2));
  }

  private static String fileName(String dxl) {
    Matcher names =
        Pattern.compile("(?is)<item\\s+name=(['\"])\\$FileNames\\1[^>]*>\\s*<text>(.*?)</text>")
            .matcher(dxl);
    if (names.find()) {
      return names.group(2).trim();
    }
    Matcher title =
        Pattern.compile("(?is)<item\\s+name=(['\"])\\$TITLE\\1[^>]*>\\s*<text>(.*?)</text>")
            .matcher(dxl);
    return title.find() ? title.group(2).trim() : null;
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

  private static String xmlText(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
