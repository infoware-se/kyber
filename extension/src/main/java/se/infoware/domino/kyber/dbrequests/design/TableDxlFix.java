package se.infoware.domino.kyber.dbrequests.design;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Table DXL repairs for round-trip.
 *
 * <p>{@code tablerow_empty_self_close} (pre-import): Domino exports rowspan placeholder rows as
 * {@code <tablerow/>}. The importer rejects those ("Expected end of tag tablerow"). Expand to
 * {@code <tablerow></tablerow>}.
 *
 * <p>{@code tablerow_orphan_par} (pre-import): Domino sometimes exports {@code <par>} / {@code
 * <pardef>} as direct children of {@code <tablerow>}. The importer then rejects the note (error
 * 6922: Unexpected tablerow element child element: par). Wrap each contiguous run of non-{@code
 * tablecell} markup in a {@code <tablecell>}.
 */
final class TableDxlFix {

  private static final Pattern EMPTY_TABLEROW =
      Pattern.compile("(?i)<tablerow\\b([^>/]*)/>");
  private static final Pattern TABLEROW =
      Pattern.compile("(?is)<tablerow\\b([^>]*)>(.*?)</tablerow>");
  private static final Pattern TABLECELL =
      Pattern.compile("(?is)<tablecell\\b[^>]*>.*?</tablecell\\s*>");

  private TableDxlFix() {}

  /**
   * Expand empty self-closing {@code <tablerow/>} and wrap orphan non-{@code tablecell} children
   * of {@code <tablerow>} so Domino's importer accepts the DXL. Idempotent when rows are already
   * well-formed.
   */
  static String prepareIncomingDxl(String dxl) {
    if (dxl == null || dxl.isBlank() || !dxl.toLowerCase(Locale.ROOT).contains("<tablerow")) {
      return dxl;
    }
    String expanded = expandEmptyTablerows(dxl);
    Matcher row = TABLEROW.matcher(expanded);
    StringBuilder out = new StringBuilder(expanded.length() + 64);
    boolean changed = !expanded.equals(dxl);
    while (row.find()) {
      String attrs = row.group(1) == null ? "" : row.group(1);
      String body = row.group(2) == null ? "" : row.group(2);
      String fixedBody = wrapOrphanRuns(body);
      if (!fixedBody.equals(body)) {
        changed = true;
      }
      row.appendReplacement(
          out, Matcher.quoteReplacement("<tablerow" + attrs + ">" + fixedBody + "</tablerow>"));
    }
    if (!changed) {
      return dxl;
    }
    row.appendTail(out);
    return out.toString();
  }

  /** {@code <tablerow/>} / {@code <tablerow attrs/>} → explicit empty element. */
  private static String expandEmptyTablerows(String dxl) {
    Matcher m = EMPTY_TABLEROW.matcher(dxl);
    if (!m.find()) {
      return dxl;
    }
    StringBuilder out = new StringBuilder(dxl.length() + 32);
    m.reset();
    while (m.find()) {
      String attrs = m.group(1) == null ? "" : m.group(1);
      m.appendReplacement(out, Matcher.quoteReplacement("<tablerow" + attrs + "></tablerow>"));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String wrapOrphanRuns(String body) {
    if (body == null || body.isBlank()) {
      return body;
    }
    // Nested <table> (not <tablecell>): outer <tablerow> regex truncates at the inner
    // </tablerow> and would corrupt markup — leave those rows alone.
    if (Pattern.compile("(?i)<table(?!cell)\\b").matcher(body).find()) {
      return body;
    }
    Matcher cell = TABLECELL.matcher(body);
    StringBuilder fixed = new StringBuilder(body.length() + 32);
    int last = 0;
    boolean any = false;
    while (cell.find()) {
      String between = body.substring(last, cell.start());
      String wrapped = wrapIfHasElement(between);
      if (!wrapped.equals(between)) {
        any = true;
      }
      fixed.append(wrapped);
      fixed.append(cell.group());
      last = cell.end();
    }
    String tail = body.substring(last);
    String wrappedTail = wrapIfHasElement(tail);
    if (!wrappedTail.equals(tail)) {
      any = true;
    }
    fixed.append(wrappedTail);
    return any ? fixed.toString() : body;
  }

  private static String wrapIfHasElement(String segment) {
    if (segment == null || segment.isBlank()) {
      return segment == null ? "" : segment;
    }
    String trimmed = segment.trim();
    if (trimmed.isEmpty() || trimmed.charAt(0) != '<') {
      return segment;
    }
    // Already a tablecell run (shouldn't happen here) or comment-only — leave whitespace alone.
    if (trimmed.regionMatches(true, 0, "<tablecell", 0, 10)) {
      return segment;
    }
    return "<tablecell>" + trimmed + "</tablecell>";
  }
}
