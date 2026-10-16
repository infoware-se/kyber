package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Document;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Note id as Domino writes it in DXL and in an export file name, for example {@code 368a} in
 * {@code SmokeAgentFormula-368a.dxl}. It is local to one NSF.
 */
final class NoteIds {

  private static final Pattern DXL_NOTE_ID =
      Pattern.compile("(?i)<noteinfo\\b[^>]*\\bnoteid=(['\"])(?:NT)?([0-9A-Fa-f]{1,8})\\1");

  private NoteIds() {}

  static String of(Document doc) {
    if (doc == null) {
      return null;
    }
    try {
      return format(doc.getNoteID());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** {@code 0x368A} becomes {@code 368a}. Zero means the note is not saved. */
  static String format(int noteId) {
    if (noteId == 0) {
      return null;
    }
    return Integer.toHexString(noteId);
  }

  static String fromDxl(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    Matcher match = DXL_NOTE_ID.matcher(dxl);
    if (!match.find()) {
      return null;
    }
    try {
      return format(Integer.parseUnsignedInt(match.group(2), 16));
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
