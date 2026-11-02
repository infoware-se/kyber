package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.FrameContent;
import com.hcl.domino.design.Frameset;
import com.hcl.domino.design.FramesetLayout;
import com.hcl.domino.design.FramesetLayout.FrameSizeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frameset DXL repairs for round-trip.
 *
 * <ul>
 *   <li>{@code frameset_empty_cells} (pre-import): Domino sometimes exports nested {@code <frameset
 *       …/>} with no children; the importer then rejects the note (error 6925). Expand to N empty
 *       {@code <frame/>} children from {@code columns}/{@code rows}.
 *   <li>{@code frameset_columns_rows} (post-import, {@link DxlImportFixes}): re-apply sizes via JNX
 *       {@link FramesetLayout} after Domino mis-builds {@code $FrameSet}.
 * </ul>
 */
final class FramesetDxlFix {

  private static final Pattern FRAMESET_OPEN =
      Pattern.compile("<frameset\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern EMPTY_FRAMESET =
      Pattern.compile(
          "<frameset\\b([^>]*?)\\s*(?:/>|>\\s*</frameset>)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern COLUMNS =
      Pattern.compile("\\bcolumns\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
  private static final Pattern ROWS =
      Pattern.compile("\\brows\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);

  private FramesetDxlFix() {}

  /**
   * Expand empty / self-closing nested {@code <frameset>} tags so Domino's importer accepts the
   * DXL. Call before {@code DxlImporter}. Idempotent when every frameset already has children.
   */
  static String prepareIncomingDxl(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return dxl;
    }
    Matcher m = EMPTY_FRAMESET.matcher(dxl);
    StringBuilder out = new StringBuilder(dxl.length() + 64);
    while (m.find()) {
      String attrs = m.group(1) == null ? "" : m.group(1).trim();
      if (attrs.endsWith("/")) {
        attrs = attrs.substring(0, attrs.length() - 1).trim();
      }
      int n = cellCountFromAttrs(attrs);
      if (n < 1) {
        n = 1;
      }
      StringBuilder repl = new StringBuilder();
      repl.append("<frameset");
      if (!attrs.isEmpty()) {
        repl.append(' ').append(attrs);
      }
      repl.append('>');
      for (int i = 0; i < n; i++) {
        repl.append("<frame/>");
      }
      repl.append("</frameset>");
      m.appendReplacement(out, Matcher.quoteReplacement(repl.toString()));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static int cellCountFromAttrs(String attrs) {
    if (attrs == null || attrs.isBlank()) {
      return 0;
    }
    Matcher cols = COLUMNS.matcher(attrs);
    if (cols.find()) {
      return parseSizes(cols.group(1)).size();
    }
    Matcher rows = ROWS.matcher(attrs);
    if (rows.find()) {
      return parseSizes(rows.group(1)).size();
    }
    return 0;
  }

  /**
   * After a frameset DXL import, push {@code columns}/{@code rows} from the DXL onto the live
   * layout (root and nested framesets in document order). No-op when JNX cannot open the frameset.
   */
  static void applyAfterImport(Database db, String name, String dxl) {
    if (db == null || name == null || name.isBlank() || dxl == null || dxl.isBlank()) {
      return;
    }
    try {
      Optional<Frameset> found = db.getDesign().getFrameset(name.trim());
      if (found.isEmpty()) {
        return;
      }
      Frameset frameset = found.get();
      FramesetLayout root = frameset.getLayout();
      List<SizeList> lists = sizeListsFromDxl(dxl);
      if (lists.isEmpty()) {
        return;
      }
      // DXL lists every <frameset> open tag in document order; walk the layout tree the same way.
      applyTree(root, lists, new int[] {0});
      frameset.save();
    } catch (RuntimeException ignored) {
      // Some JNX builds throw on getFrameset / getLayout; import already stored the note.
    }
  }

  /** Parse every {@code <frameset ...>} open tag's columns or rows list, root first. */
  static List<SizeList> sizeListsFromDxl(String dxl) {
    List<SizeList> out = new ArrayList<>();
    Matcher open = FRAMESET_OPEN.matcher(dxl);
    while (open.find()) {
      String attrs = open.group(1);
      Matcher cols = COLUMNS.matcher(attrs);
      if (cols.find()) {
        out.add(new SizeList(parseSizes(cols.group(1)), true));
        continue;
      }
      Matcher rows = ROWS.matcher(attrs);
      if (rows.find()) {
        out.add(new SizeList(parseSizes(rows.group(1)), false));
      }
    }
    return out;
  }

  static List<Size> parseSizes(String raw) {
    List<Size> out = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    for (String token : raw.trim().split("\\s+")) {
      parseSize(token).ifPresent(out::add);
    }
    return out;
  }

  static Optional<Size> parseSize(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    String t = token.trim().toLowerCase(Locale.ROOT);
    try {
      if (t.endsWith("px")) {
        return Optional.of(new Size(Integer.parseInt(t.substring(0, t.length() - 2)), FrameSizeUnit.PIXELS));
      }
      if (t.endsWith("%")) {
        return Optional.of(
            new Size(Integer.parseInt(t.substring(0, t.length() - 1)), FrameSizeUnit.PERCENTAGE));
      }
      return Optional.of(new Size(Integer.parseInt(t), FrameSizeUnit.RELATIVE));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static void applyTree(FramesetLayout layout, List<SizeList> lists, int[] index) {
    if (layout == null || lists == null || index[0] >= lists.size()) {
      return;
    }
    applySizeList(layout, lists.get(index[0]++));
    for (int i = 0; i < layout.getCount(); i++) {
      FrameContent<?> content = layout.getContent(i);
      if (content instanceof FramesetLayout child) {
        applyTree(child, lists, index);
      }
    }
  }

  private static void applySizeList(FramesetLayout layout, SizeList list) {
    if (layout == null || list == null || list.sizes().isEmpty()) {
      return;
    }
    int n = Math.min(layout.getCount(), list.sizes().size());
    for (int i = 0; i < n; i++) {
      Size size = list.sizes().get(i);
      layout.getContent(i).setSize(size.value(), size.unit());
    }
  }

  record Size(int value, FrameSizeUnit unit) {}

  record SizeList(List<Size> sizes, boolean columns) {}
}
