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
      case INTERFACE -> "se/infoware/keep/DefaultHelloInterface.java";
      case ENUM -> "se/infoware/keep/DefaultHelloEnum.java";
      default -> "se/infoware/keep/DefaultHello.java";
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
      int end = raw.length;
      if (raw[end - 1] == 0) {
        end--;
      }
      return new String(raw, 42, end - 42, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }
}
