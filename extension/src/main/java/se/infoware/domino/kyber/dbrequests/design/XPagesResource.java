package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** XPages project files ({@code .classpath}, {@code WEB-INF/faces-config.xml}, and the rest). */
final class XPagesResource {

  private static final Pattern FILE_NAMES =
      Pattern.compile(
          "(?i)(<item\\s+name=(['\"])\\$FileNames\\2[^>]*>\\s*<text>)[^<]*(</text>)");

  private XPagesResource() {}

  /** Set the note name, {@code $TITLE} and {@code $FileNames}. Paths under {@code WEB-INF} stay. */
  static String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    String wanted = name.trim().replace('\\', '/');
    String out = DesignDxl.withName(dxl, wanted, "note");
    return FILE_NAMES.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(wanted) + "$3");
  }
}
