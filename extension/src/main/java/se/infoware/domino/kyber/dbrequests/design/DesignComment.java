package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Document;
import com.hcl.domino.design.DesignElement;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Designer comment written when Kyber creates or updates a design note.
 *
 * <p>{@code Kyber 0.24.15 Infoware}, or {@code Kyber 0.24.15 Infoware - } plus the comment that was
 * already there. An earlier Kyber prefix is replaced so a second update does not stack it.
 */
final class DesignComment {

  private static final Pattern KYBER_PREFIX =
      Pattern.compile("^Kyber \\d+\\.\\d+\\.\\d+ Infoware(?: - (.*))?$");
  private static final Pattern COMMENT_ATTRIBUTE =
      Pattern.compile("(?i)\\bcomment=(['\"])(.*?)\\1");

  private DesignComment() {}

  static String apply(String existing, String version) {
    String original = withoutKyberPrefix(existing);
    String prefix = "Kyber " + version + " Infoware";
    if (original.isEmpty()) {
      return prefix;
    }
    return prefix + " - " + original;
  }

  static String withoutKyberPrefix(String comment) {
    if (comment == null) {
      return "";
    }
    String trimmed = comment.trim();
    while (true) {
      Matcher matcher = KYBER_PREFIX.matcher(trimmed);
      if (!matcher.matches()) {
        return trimmed;
      }
      String rest = matcher.group(1);
      if (rest == null || rest.isBlank()) {
        return "";
      }
      trimmed = rest.trim();
    }
  }

  static String version() {
    try (InputStream in = DesignComment.class.getResourceAsStream("/kyber.properties")) {
      if (in == null) {
        return "0.0.0";
      }
      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("kyber.version", "").trim();
      if (version.isEmpty() || version.contains("${")) {
        return "0.0.0";
      }
      return version;
    } catch (Exception e) {
      return "0.0.0";
    }
  }

  /** Comment attribute on the imported DXL, or null when the payload does not set one. */
  static String commentAttribute(String dxl) {
    if (dxl == null || dxl.isEmpty()) {
      return null;
    }
    Matcher matcher = COMMENT_ATTRIBUTE.matcher(dxl);
    if (!matcher.find()) {
      return null;
    }
    return matcher.group(2);
  }

  /**
   * The comment just imported wins. {@code getComment()} can still return the previous Kyber prefix
   * after a database-script import, and stamping that would drop the text in the DXL.
   */
  static String sourceComment(String dxl, String loadedComment) {
    String imported = commentAttribute(dxl);
    return imported != null ? imported : loadedComment;
  }

  static void stamp(DesignElement element, String dxl) {
    if (element == null) {
      return;
    }
    element.setComment(apply(sourceComment(dxl, element.getComment()), version()));
  }

  static void stamp(Document document, String dxl) {
    if (document == null) {
      return;
    }
    String current = "";
    if (document.hasItem("$Comment")) {
      current =
          document.getFirstItem("$Comment").map(item -> item.get(String.class, "")).orElse("");
    }
    document.replaceItemValue("$Comment", apply(sourceComment(dxl, current), version()));
  }
}
