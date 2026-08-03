package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domino Designer Code &gt; Java kinds: class, interface, enum ({@code $Flags} like {@code
 * 34567Cg~[}).
 */
final class JavaSourceKinds {

  static final String CLASS = "class";
  static final String INTERFACE = "interface";
  static final String ENUM = "enum";

  static final String[] ALL = {CLASS, INTERFACE, ENUM};

  private static final Pattern TITLE =
      Pattern.compile("\\$TITLE['\"]?[^>]*>\\s*<text>([^<]+)</text>", Pattern.CASE_INSENSITIVE);
  private static final Pattern RAW =
      Pattern.compile(
          "<rawitemdata[^>]*>\\s*([A-Za-z0-9+/=\\s]+)\\s*</rawitemdata>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private JavaSourceKinds() {}

  static String normalize(String kind) throws KeepExceptionWrongDataRequest {
    if (kind == null || kind.isBlank()) {
      return CLASS;
    }
    String k = kind.trim().toLowerCase(Locale.ROOT);
    return switch (k) {
      case "class", "java" -> CLASS;
      case "interface", "iface" -> INTERFACE;
      case "enum", "enumeration" -> ENUM;
      default ->
          throw new KeepExceptionWrongDataRequest(
              "Unknown javasource kind '" + kind + "'; expected class|interface|enum");
    };
  }

  static String embeddedSuffix(String kind) throws KeepExceptionWrongDataRequest {
    return switch (normalize(kind)) {
      case INTERFACE -> "-interface";
      case ENUM -> "-enum";
      default -> "";
    };
  }

  static String defaultName(String kind) throws KeepExceptionWrongDataRequest {
    return switch (normalize(kind)) {
      case INTERFACE -> "se/infoware/keep/NewJavaInterface.java";
      case ENUM -> "se/infoware/keep/NewJavaEnum.java";
      default -> "se/infoware/keep/NewJava.java";
    };
  }

  static String detect(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    String body = filePayload(dxl);
    if (body != null) {
      String compact = body.replace('\r', ' ');
      if (compact.contains("public interface ")
          || compact.contains("\ninterface ")
          || compact.startsWith("interface ")) {
        return INTERFACE;
      }
      if (compact.contains("public enum ")
          || compact.contains("\nenum ")
          || compact.startsWith("enum ")) {
        return ENUM;
      }
      if (compact.contains("public class ")
          || compact.contains("\nclass ")
          || compact.startsWith("class ")) {
        return CLASS;
      }
    }
    Matcher tm = TITLE.matcher(dxl);
    if (tm.find()) {
      String title = tm.group(1).toLowerCase(Locale.ROOT);
      if (title.contains("interface")) {
        return INTERFACE;
      }
      if (title.contains("enum")) {
        return ENUM;
      }
      if (title.endsWith(".java")) {
        return CLASS;
      }
    }
    return null;
  }

  static String detectOrDefault(String dxl) {
    String d = detect(dxl);
    return d == null ? CLASS : d;
  }

  /** Set {@code $TITLE} / {@code $FileNames} and rewrite package/type in {@code $FileData}. */
  static String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    String wanted = name.trim().replace('\\', '/');
    if (!wanted.toLowerCase(Locale.ROOT).endsWith(".java")) {
      throw new KeepExceptionWrongDataRequest("javasource name must end with .java");
    }
    String pathNoExt = wanted.substring(0, wanted.length() - 5);
    int slash = pathNoExt.lastIndexOf('/');
    String simpleName = slash >= 0 ? pathNoExt.substring(slash + 1) : pathNoExt;
    String pkg = slash >= 0 ? pathNoExt.substring(0, slash).replace('/', '.') : "";
    if (simpleName.isBlank()) {
      throw new KeepExceptionWrongDataRequest("javasource name has empty type name");
    }

    String out = dxl;
    out =
        out.replaceAll(
            "(?i)(<item\\s+name=['\"]\\$?TITLE['\"][^>]*>\\s*<text>)[^<]*(</text>)",
            "$1" + Matcher.quoteReplacement(wanted) + "$2");
    out =
        out.replaceAll(
            "(?i)(<item\\s+name=['\"]\\$?FileNames['\"][^>]*>\\s*<text>)[^<]*(</text>)",
            "$1" + Matcher.quoteReplacement(wanted) + "$2");

    // Rewrite package + public type name inside $FileData (host file attachment).
    Pattern fileDataPat =
        Pattern.compile(
            "(?is)(<item\\s+name=['\"]\\$?FileData['\"][^>]*>\\s*<rawitemdata[^>]*>)([A-Za-z0-9+/=\\s]+)(</rawitemdata>)");
    Matcher fileData = fileDataPat.matcher(out);
    if (!fileData.find()) {
      return out;
    }
    try {
      byte[] raw = Base64.getDecoder().decode(fileData.group(2).replaceAll("\\s+", ""));
      if (raw.length <= 42) {
        return out;
      }
      int oldSize = raw.length - 42;
      String source = new String(raw, 42, oldSize, StandardCharsets.UTF_8);
      String rewritten = rewriteJavaSource(source, pkg, simpleName);
      byte[] body = rewritten.getBytes(StandardCharsets.UTF_8);
      byte[] next = new byte[42 + body.length];
      System.arraycopy(raw, 0, next, 0, 42);
      System.arraycopy(body, 0, next, 42, body.length);
      writeLe32(next, 8, body.length);
      int off26 = readLe32(raw, 26);
      if (off26 == oldSize + 18) {
        writeLe32(next, 26, body.length + 18);
      } else if (off26 == oldSize) {
        writeLe32(next, 26, body.length);
      }
      if (readLe32(raw, 28) == oldSize) {
        writeLe32(next, 28, body.length);
      }
      if (readLe32(raw, 30) == oldSize) {
        writeLe32(next, 30, body.length);
      }
      String b64 = Base64.getEncoder().encodeToString(next);
      out =
          out.substring(0, fileData.start(2))
              + "\n"
              + b64
              + "\n"
              + out.substring(fileData.end(2));
      out =
          out.replaceAll(
              "(?i)(<item\\s+name=['\"]\\$?FileSize['\"][^>]*>\\s*<number>)\\d+(</number>)",
              "$1" + body.length + "$2");
      // Drop compiled class items — mismatched .class makes DXL import fail (id=519)
      out =
          out.replaceAll(
              "(?is)<item\\s+name=['\"]\\$Class(?:Data0|Size0|IndexItem)['\"][^>]*>.*?</item>",
              "");
      return out;
    } catch (IllegalArgumentException e) {
      throw new KeepExceptionWrongDataRequest("invalid javasource $FileData encoding");
    }
  }

  private static int readLe32(byte[] raw, int offset) {
    if (offset + 3 >= raw.length) {
      return -1;
    }
    return (raw[offset] & 0xff)
        | ((raw[offset + 1] & 0xff) << 8)
        | ((raw[offset + 2] & 0xff) << 16)
        | ((raw[offset + 3] & 0xff) << 24);
  }

  private static void writeLe32(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value & 0xff);
    raw[offset + 1] = (byte) ((value >>> 8) & 0xff);
    raw[offset + 2] = (byte) ((value >>> 16) & 0xff);
    raw[offset + 3] = (byte) ((value >>> 24) & 0xff);
  }

  static String rewriteJavaSource(String source, String pkg, String simpleName) {
    String out = source;
    if (pkg == null || pkg.isBlank()) {
      out = out.replaceFirst("(?m)^\\s*package\\s+[^;]+;\\s*", "");
    } else {
      if (Pattern.compile("(?m)^\\s*package\\s+").matcher(out).find()) {
        out = out.replaceFirst("(?m)^(\\s*package\\s+)[^;]+;", "$1" + pkg + ";");
      } else {
        out = "package " + pkg + ";\r\n\r\n" + out;
      }
    }
    out =
        out.replaceFirst(
            "(public\\s+(?:abstract\\s+|final\\s+)?)(class|interface|enum)(\\s+)\\w+",
            "$1$2$3" + Matcher.quoteReplacement(simpleName));
    return out;
  }

  private static String filePayload(String dxl) {
    Matcher m = RAW.matcher(dxl);
    if (!m.find()) {
      return null;
    }
    try {
      byte[] raw = Base64.getDecoder().decode(m.group(1).replaceAll("\\s+", ""));
      if (raw.length <= 42) {
        return null;
      }
      return new String(raw, 42, raw.length - 42, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }
}
