package se.infoware.domino.kyber.dbrequests.design;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formula DXL repairs for round-trip.
 *
 * <p>{@code dnt_begin_without_end} (pre-import): Domino exports some hide-when (and other) formulas
 * as {@code REM \{DNT Begin\}; …} without a matching {@code REM \{DNT End\};}. The importer then
 * rejects the note with error 1301 (No main or selection expression in formula). Strip the
 * unmatched Begin marker so the real expression remains.
 */
final class FormulaDxlFix {

  private static final Pattern FORMULA =
      Pattern.compile("(?is)(<formula\\b[^>]*>)(.*?)(</formula\\s*>)");
  private static final Pattern DNT_BEGIN =
      Pattern.compile("(?is)REM\\s*\\{\\s*DNT\\s+Begin\\s*\\}\\s*;\\s*");
  private static final Pattern DNT_END =
      Pattern.compile("(?is)REM\\s*\\{\\s*DNT\\s+End\\s*\\}\\s*;?");

  private FormulaDxlFix() {}

  static String prepareIncomingDxl(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return dxl;
    }
    String lower = dxl.toLowerCase(Locale.ROOT);
    if (!lower.contains("dnt begin") || !lower.contains("<formula")) {
      return dxl;
    }
    Matcher m = FORMULA.matcher(dxl);
    StringBuilder out = new StringBuilder(dxl.length());
    boolean changed = false;
    while (m.find()) {
      String open = m.group(1);
      String body = m.group(2);
      String close = m.group(3);
      String fixed = fixFormulaBody(body);
      if (!fixed.equals(body)) {
        changed = true;
      }
      m.appendReplacement(out, Matcher.quoteReplacement(open + fixed + close));
    }
    if (!changed) {
      return dxl;
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String fixFormulaBody(String body) {
    if (body == null || body.isBlank()) {
      return body;
    }
    boolean hasBegin = DNT_BEGIN.matcher(body).find();
    boolean hasEnd = DNT_END.matcher(body).find();
    if (hasBegin && !hasEnd) {
      return DNT_BEGIN.matcher(body).replaceAll("");
    }
    return body;
  }
}
