package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.ImageResource;

/**
 * The application icon ({@code $DBIcon}). Designer checks "Allow design refresh or replace to
 * modify the application icon" when {@code $Flags} does not contain {@code P}.
 */
final class DbIcon {

  /** {@code $Flags} letter that prohibits design refresh of the application icon. */
  static final char PROHIBIT_REFRESH = 'P';

  private DbIcon() {}

  static String flagsAllowingRefresh(String flags) {
    if (flags == null || flags.isEmpty()) {
      return flags == null ? "" : flags;
    }
    return flags.replace(String.valueOf(PROHIBIT_REFRESH), "");
  }

  /**
   * DXL import writes {@code $FlagsExt=D} on {@code $DBIcon}. Template icons that design refresh
   * copies do not have that item.
   */
  static boolean isImportOnlyFlagsExt(String flagsExt) {
    return "D".equals(flagsExt);
  }

  static void allowDesignRefresh(Database db) {
    db.getDesign()
        .getImageResource(DesignElementLookup.DBICON_NAME)
        .ifPresent(DbIcon::allowDesignRefresh);
  }

  private static void allowDesignRefresh(ImageResource image) {
    Document doc = image.getDocument();
    boolean changed = false;
    if (doc.hasItem("$Flags")) {
      String flags =
          doc.getFirstItem("$Flags").map(item -> item.get(String.class, "")).orElse("");
      String allowed = flagsAllowingRefresh(flags);
      if (!allowed.equals(flags)) {
        doc.replaceItemValue("$Flags", allowed);
        changed = true;
      }
    }
    if (doc.hasItem("$FlagsExt")) {
      String ext =
          doc.getFirstItem("$FlagsExt").map(item -> item.get(String.class, "")).orElse("");
      if (isImportOnlyFlagsExt(ext)) {
        doc.removeItem("$FlagsExt");
        changed = true;
      }
    }
    if (changed) {
      doc.save();
    }
  }
}
