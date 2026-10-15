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
 * <p>{@code Kyber 0.24.4 Infoware}, or {@code Kyber 0.24.4 Infoware - } plus the comment that was
 * already there. An earlier Kyber prefix is replaced so a second update does not stack it.
 */
final class DesignComment {

  private static final Pattern KYBER_PREFIX =
      Pattern.compile("^Kyber \\d+\\.\\d+\\.\\d+ Infoware(?: - (.*))?$");

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

  static void stamp(DesignElement element) {
    if (element == null) {
      return;
    }
    element.setComment(apply(element.getComment(), version()));
  }

  static void stamp(Document document) {
    if (document == null) {
      return;
    }
    String current = "";
    if (document.hasItem("$Comment")) {
      current =
          document.getFirstItem("$Comment").map(item -> item.get(String.class, "")).orElse("");
    }
    document.replaceItemValue("$Comment", apply(current, version()));
  }
}
