package se.infoware.domino.kyber.dbrequests.design;

import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Domino DXL import matches design notes by name <em>or</em> alias. Two notes that share an alias
 * (e.g. framesets {@code MainFrameset Notes} and {@code MainFramesetWeb} both {@code
 * alias='MainFrameset'}) collapse to one note even when import reports success for each row.
 *
 * <p>Before a bulk import, rewrite colliding aliases to the note's own {@code name} so each note
 * stays distinct. Prefer keeping the original alias on at most one note per (type, alias) group —
 * the one whose name already equals the alias when present.
 */
final class AliasCollisionFix {

  private AliasCollisionFix() {}

  /** Returns a new list; elements without DXL / delete rows are left unchanged. */
  static List<JsonObject> prepareBatch(List<JsonObject> elements) {
    if (elements == null || elements.isEmpty()) {
      return elements;
    }
    List<JsonObject> out = new ArrayList<>(elements.size());
    for (JsonObject el : elements) {
      out.add(el == null ? null : el.copy());
    }

    // type|aliasLower → indexes into out that share that alias
    Map<String, List<Integer>> byTypeAlias = new LinkedHashMap<>();
    for (int i = 0; i < out.size(); i++) {
      JsonObject el = out.get(i);
      if (el == null || el.getBoolean("delete", false)) {
        continue;
      }
      String type = DesignBulkTypes.normalize(el.getString("type", ""));
      String dxl = el.getString("dxl", "");
      if (type.isEmpty() || dxl == null || dxl.isBlank()) {
        continue;
      }
      String alias = DesignDxl.rootAlias(dxl);
      if (alias == null || alias.isBlank()) {
        continue;
      }
      String key = type + "\0" + alias.toLowerCase(Locale.ROOT);
      byTypeAlias.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
    }

    for (List<Integer> idxs : byTypeAlias.values()) {
      if (idxs.size() < 2) {
        continue;
      }
      int keep = indexToKeepOriginalAlias(out, idxs);
      for (int i : idxs) {
        if (i == keep) {
          continue;
        }
        JsonObject el = out.get(i);
        String dxl = el.getString("dxl", "");
        String name = DesignDxl.designName(dxl);
        if (name == null || name.isBlank()) {
          name = el.getString("name");
        }
        if (name == null || name.isBlank()) {
          continue;
        }
        el.put("dxl", DesignDxl.withAlias(dxl, name.trim()));
      }
    }
    return out;
  }

  private static int indexToKeepOriginalAlias(List<JsonObject> out, List<Integer> idxs) {
    for (int i : idxs) {
      JsonObject el = out.get(i);
      String dxl = el.getString("dxl", "");
      String name = DesignDxl.designName(dxl);
      String alias = DesignDxl.rootAlias(dxl);
      if (name != null && alias != null && name.equalsIgnoreCase(alias)) {
        return i;
      }
    }
    return idxs.get(0);
  }
}
