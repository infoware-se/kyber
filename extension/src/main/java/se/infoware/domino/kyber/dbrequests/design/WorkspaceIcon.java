package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DocumentClass;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Notes workspace icon is the icon note, separate from the {@code $DBIcon} image resource. The
 * note also holds the launch settings. A write of {@code $DBIcon} copies the picture onto that
 * note and leaves every other part of the note, including the launch settings, as it was.
 */
final class WorkspaceIcon {

  private static final Pattern IMAGE =
      Pattern.compile("(?is)<(png|gif|jpeg)\\b[^>]*>.*?</\\1>");
  private static final Pattern LAUNCH =
      Pattern.compile("(?is)<launchsettings\\b[^>]*>.*?</launchsettings>|<launchsettings\\b[^>]*/>");
  private static final Pattern ROOT_CLOSE =
      Pattern.compile("(?is)</(iconnote|note)\\s*>\\s*$");

  private WorkspaceIcon() {}

  /**
   * Copy the picture from an image-resource DXL onto the icon note. Does nothing when the DXL has
   * no {@code png}, {@code gif}, or {@code jpeg} element, or when the database has no icon note.
   */
  static void sync(Database db, DxlImporter importer, String imageResourceDxl) throws Exception {
    String image = imageElement(imageResourceDxl);
    if (image == null || db == null) {
      return;
    }
    int noteId = db.getSpecialNoteId(DocumentClass.ICON);
    if (noteId == 0) {
      return;
    }
    Optional<Document> note = db.getDocumentById(noteId);
    if (note.isEmpty()) {
      return;
    }
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String current = exporter.exportDocument(note.get());
    String updated = withImage(current, image);
    String launch = launchSettings(current);
    if (!launch.isEmpty() && !updated.contains(launch)) {
      throw new KeepExceptionServer("Icon note launch settings were not preserved");
    }
    DxlDesignPatch.importIconNote(importer, updated, db);
  }

  /** The first {@code png}, {@code gif}, or {@code jpeg} element, or null. */
  static String imageElement(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    Matcher match = IMAGE.matcher(dxl);
    return match.find() ? match.group() : null;
  }

  /**
   * Icon-note DXL with {@code imageElement} in place of the existing picture. Launch settings and
   * the rest of the note stay. When the note has no picture element yet, the image is inserted
   * before the closing root tag.
   */
  static String withImage(String iconDxl, String imageElement) {
    if (iconDxl == null || iconDxl.isBlank() || imageElement == null || imageElement.isBlank()) {
      return iconDxl;
    }
    Matcher picture = IMAGE.matcher(iconDxl);
    if (picture.find()) {
      return picture.replaceFirst(Matcher.quoteReplacement(imageElement));
    }
    Matcher close = ROOT_CLOSE.matcher(iconDxl);
    if (!close.find()) {
      return iconDxl;
    }
    return iconDxl.substring(0, close.start()) + imageElement + iconDxl.substring(close.start());
  }

  static String launchSettings(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return "";
    }
    Matcher match = LAUNCH.matcher(dxl);
    return match.find() ? match.group() : "";
  }
}
