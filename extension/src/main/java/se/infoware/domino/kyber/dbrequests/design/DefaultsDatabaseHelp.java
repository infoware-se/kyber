package se.infoware.domino.kyber.dbrequests.design;

import java.util.List;

/**
 * Usage text written into About and Using when {@code kyber.nsf} is created. Both documents get
 * the same paragraphs, matching the layout chosen in the About document. While this text remains,
 * those documents are not design templates.
 */
final class DefaultsDatabaseHelp {

  static final String NOT_A_TEMPLATE_SENTENCE = "Kyber does not use this document as a template.";

  private static final List<Block> BLOCKS =
      List.of(
          block("1", "24pt", "bold", null, "", true),
          block("2", "24pt", "bold", null, "Kyber Design Elements", false),
          block(
              "2",
              "12pt",
              null,
              "#5F6B76",
              "Kyber — Infoware REST API for design elements",
              false),
          block("3", null, null, null, "", false),
          block("4", "12pt", "bold", null, "What this database is", false),
          block(
              "3",
              "12pt",
              null,
              null,
              "This database stores the design templates Kyber uses when a call does not send its own DXL.",
              false),
          block("3", null, null, null, "", false),
          block("4", "12pt", "bold", null, "Created once", false),
          block(
              "3",
              "12pt",
              null,
              null,
              "Kyber creates this file the first time a design element is written to any database.",
              false),
          block(
              "3",
              "12pt",
              null,
              null,
              "It is created only when the file does not already exist.",
              false),
          block("3", null, null, null, "", false),
          block("4", "12pt", "bold", null, "Templates you add", false),
          block("3", "12pt", null, null, "Add the elements you want to reuse in Domino Designer.", false),
          block(
              "3",
              "12pt",
              null,
              null,
              "For each type, Kyber uses the first element in this database, ordered by name without regard to case.",
              false),
          block(
              "3",
              "12pt",
              null,
              null,
              "Agents, script libraries, and Java sources are chosen separately for each kind.",
              false),
          block(
              "3",
              "12pt",
              null,
              null,
              "When this database has no element of that type, Kyber uses the minimal template shipped with the API.",
              false),
          block("3", null, null, null, "", false),
          block("4", "12pt", "bold", null, "Not used as templates", false),
          block("3", "12pt", null, null, "The database icon is not an image template.", false),
          block("3", null, null, null, "", false),
          block("3", null, "italic", "maroon", NOT_A_TEMPLATE_SENTENCE, false));

  private DefaultsDatabaseHelp() {}

  static String aboutDxl() {
    return document("helpaboutdocument");
  }

  static String usingDxl() {
    return document("helpusingdocument");
  }

  /** Notes client opens the About document every time this database is opened. */
  static String notesLaunchAboutDxl() {
    return "<?xml version='1.0' encoding='utf-8'?>"
        + "<database xmlns='http://www.lotus.com/dxl' version='14.5'>"
        + "<launchsettings><noteslaunch whenopened='openaboutdocument'/></launchsettings>"
        + "</database>";
  }

  static boolean isUsageText(String dxl) {
    return dxl != null && dxl.contains(NOT_A_TEMPLATE_SENTENCE);
  }

  private static String document(String root) {
    StringBuilder body = new StringBuilder();
    for (Block block : BLOCKS) {
      body.append("<par def='").append(block.style()).append("'>");
      if (!block.text().isEmpty() || block.runWhenBlank()) {
        body.append("<run>").append(font(block)).append(xml(block.text())).append("</run>");
      }
      body.append("</par>");
    }
    return "<?xml version='1.0' encoding='utf-8'?>"
        + "<"
        + root
        + " xmlns='http://www.lotus.com/dxl' version='14.5' publicaccess='false' default='true'"
        + " renderpassthrough='true'>"
        + "<body><richtext>"
        + "<pardef id='1' align='center'/>"
        + "<pardef id='2'/>"
        + "<pardef id='3' leftmargin='1.35in'/>"
        + "<pardef id='4' leftmargin='1in'/>"
        + body
        + "</richtext></body></"
        + root
        + ">";
  }

  private static String font(Block block) {
    StringBuilder font = new StringBuilder("<font");
    if (block.size() != null) {
      font.append(" size='").append(block.size()).append("'");
    }
    if (block.fontStyle() != null) {
      font.append(" style='").append(block.fontStyle()).append("'");
    }
    font.append(" name='Arial' pitch='variable' truetype='true' familyid='20'");
    if (block.color() != null) {
      font.append(" color='").append(block.color()).append("'");
    }
    font.append("/>");
    return font.toString();
  }

  private static Block block(
      String style,
      String size,
      String fontStyle,
      String color,
      String text,
      boolean runWhenBlank) {
    return new Block(style, size, fontStyle, color, text, runWhenBlank);
  }

  private record Block(
      String style,
      String size,
      String fontStyle,
      String color,
      String text,
      boolean runWhenBlank) {}

  private static String xml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
