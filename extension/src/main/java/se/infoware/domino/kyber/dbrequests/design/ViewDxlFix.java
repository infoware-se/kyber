package se.infoware.domino.kyber.dbrequests.design;

import java.util.regex.Pattern;

/**
 * View/folder DXL repairs for round-trip.
 *
 * <p>{@code view_readers} (pre-import): Domino exports {@code $Readers} with ACL role names from
 * the source database. Import into a fresh NSF that lacks those roles often leaves the view
 * missing or unreachable ({@code getView} empty / Domino {@code 0x1}). Strip reader items so the
 * design note is created; callers can re-apply readers later if needed.
 */
final class ViewDxlFix {

  private static final Pattern READERS_ITEM =
      Pattern.compile(
          "(?is)<item\\s+name=(['\"])\\$Readers\\1[^>]*>.*?</item\\s*>\\s*");

  private ViewDxlFix() {}

  static String prepareIncomingDxl(String dxl) {
    if (dxl == null || dxl.isBlank() || !dxl.contains("$Readers")) {
      return dxl;
    }
    return READERS_ITEM.matcher(dxl).replaceAll("");
  }
}
