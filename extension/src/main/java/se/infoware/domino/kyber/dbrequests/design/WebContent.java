package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Files in the Designer WebContent folder. Domino marks them with {@code $FlagsExt} {@code w}.
 * They are not file resources, and they are not the XPages project under {@code WEB-INF}.
 */
final class WebContent {

  /** {@code $FlagsExt} letter on a WebContent file. */
  static final char FLAG_EXT = 'w';

  private WebContent() {}

  static boolean isWebContent(String title, String flagsExt) {
    if (flagsExt == null || flagsExt.indexOf(FLAG_EXT) < 0) {
      return false;
    }
    return !isWebInfPath(title);
  }

  /** True when the path is {@code WEB-INF} or a file inside that folder. */
  static boolean isWebInfPath(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String path = name.trim().replace('\\', '/');
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    String lower = path.toLowerCase(Locale.ROOT);
    return "web-inf".equals(lower)
        || lower.startsWith("web-inf/")
        || lower.contains("/web-inf/")
        || lower.endsWith("/web-inf");
  }

  static void requireOutsideWebInf(String name) throws KeepExceptionWrongDataRequest {
    if (isWebInfPath(name)) {
      throw new KeepExceptionWrongDataRequest("webcontent must not be stored under WEB-INF");
    }
  }

  /**
   * Set the note name, {@code $TITLE} and {@code $FileNames}. Rejects a path under {@code
   * WEB-INF}.
   */
  static String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    String wanted = name.trim().replace('\\', '/');
    requireOutsideWebInf(wanted);
    String out = DesignDxl.withName(dxl, wanted, "note");
    return FILE_NAMES
        .matcher(out)
        .replaceAll("$1" + Matcher.quoteReplacement(wanted) + "$3");
  }

  private static final Pattern FILE_NAMES =
      Pattern.compile(
          "(?i)(<item\\s+name=(['\"])\\$FileNames\\2[^>]*>\\s*<text>)[^<]*(</text>)");
}
