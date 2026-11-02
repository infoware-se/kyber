package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single place for Kyber corrections that run after Domino's DXL importer has written a note.
 *
 * <p>Domino export/import is not always lossless. When a gap is confirmed, add a fix here, call it
 * from {@link #afterImport}, document it in {@code docs/dxl-roundtrip-fixes.md}, and cover the
 * fragile attribute in round-trip smoke.
 *
 * <p>In-import cleanups that must run before/during sign (for example XPage orphan {@code
 * $ClassData*} removal) stay next to that sign path but are listed in the same doc catalog.
 */
final class DxlImportFixes {

  private DxlImportFixes() {}

  /**
   * Known post-import fix ids → design types they apply to. Used by unit tests and docs to keep the
   * catalog honest.
   */
  static final Map<String, Set<String>> REGISTERED_FIXES;

  static {
    Map<String, Set<String>> fixes = new LinkedHashMap<>();
    fixes.put("frameset_columns_rows", Set.of("frameset"));
    REGISTERED_FIXES = Map.copyOf(fixes);
  }

  /** Run every registered post-import fix that matches {@code type}. */
  static void afterImport(Database db, String type, String name, String dxl) {
    if (db == null || dxl == null || dxl.isBlank()) {
      return;
    }
    String t = type == null ? "" : DesignBulkTypes.normalize(type);
    switch (t) {
      case "frameset" -> FramesetDxlFix.applyAfterImport(db, name, dxl);
      default -> {
        // No post-import fix for this type yet.
      }
    }
  }

  /** Types that currently have a {@link #afterImport} correction. */
  static boolean hasAfterImportFix(String type) {
    if (type == null || type.isBlank()) {
      return false;
    }
    String t = DesignBulkTypes.normalize(type);
    for (Set<String> types : REGISTERED_FIXES.values()) {
      if (types.contains(t)) {
        return true;
      }
    }
    return false;
  }

  static String fixIdFor(String type) {
    if (type == null || type.isBlank()) {
      return "";
    }
    String t = DesignBulkTypes.normalize(type);
    for (Map.Entry<String, Set<String>> e : REGISTERED_FIXES.entrySet()) {
      if (e.getValue().contains(t)) {
        return e.getKey();
      }
    }
    return "";
  }

  static String describe(String type) {
    return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
      case "frameset" ->
          "Re-apply columns/rows from DXL onto FramesetLayout after Domino mis-builds $FrameSet";
      default -> "";
    };
  }
}
