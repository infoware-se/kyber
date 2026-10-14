package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.ScriptLibrary;
import com.hcl.domino.dxl.DxlExporter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compile LotusScript after DXL import.
 *
 * <p>Script libraries are compiled in {@code Use}-dependency order (callees first), with retry
 * passes. Forms/views/agents compile after libraries.
 */
final class DesignLotusScriptCompile {

  private static final Pattern USE_STMT =
      Pattern.compile("(?im)^\\s*Use\\s+\"([^\"]+)\"");

  private DesignLotusScriptCompile() {}

  static JsonObject compileAll(Database db) {
    JsonArray compiled = new JsonArray();
    JsonArray skipped = new JsonArray();
    JsonArray errors = new JsonArray();
    Set<String> done = new HashSet<>();

    compileScriptLibrariesOrdered(db, compiled, skipped, errors, done);

    db.getDesign()
        .getDatabaseScriptLibrary()
        .ifPresent(
            el -> compileOne(el, "databasescript", null, compiled, skipped, errors, done));

    SharedActionsNotes.stream(db)
        .forEach(el -> compileOne(el, "sharedactions", SharedActionsNotes.nameOf(el), compiled, skipped, errors, done));

    compileStream(db.getDesign().getAgents(), "agent", compiled, skipped, errors, done);
    compileStream(db.getDesign().getForms(), "form", compiled, skipped, errors, done);
    compileStream(db.getDesign().getSubforms(), "subform", compiled, skipped, errors, done);
    compileStream(db.getDesign().getViews(), "view", compiled, skipped, errors, done);
    compileStream(db.getDesign().getFolders(), "folder", compiled, skipped, errors, done);
    compileStream(db.getDesign().getPages(), "page", compiled, skipped, errors, done);
    compileStream(db.getDesign().getOutlines(), "outline", compiled, skipped, errors, done);
    compileStream(db.getDesign().getFramesets(), "frameset", compiled, skipped, errors, done);
    compileStream(db.getDesign().getSharedFields(), "sharedfield", compiled, skipped, errors, done);
    compileStream(
        db.getDesign().getSharedColumns(), "sharedcolumn", compiled, skipped, errors, done);

    return new JsonObject()
        .put("compiledCount", compiled.size())
        .put("skippedCount", skipped.size())
        .put("errorCount", errors.size())
        .put("compiled", compiled)
        .put("skipped", skipped)
        .put("errors", errors);
  }

  /**
   * Bottom-up: libraries with no {@code Use} first, then dependents. Three passes so late
   * successes unlock the next tier.
   */
  private static void compileScriptLibrariesOrdered(
      Database db,
      JsonArray compiled,
      JsonArray skipped,
      JsonArray errors,
      Set<String> done) {
    List<ScriptLibrary> libs = db.getDesign().getScriptLibraries().toList();
    if (libs.isEmpty()) {
      return;
    }

    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    Map<String, ScriptLibrary> byName = new LinkedHashMap<>();
    Map<String, List<String>> uses = new HashMap<>();

    for (ScriptLibrary lib : libs) {
      String name = primaryTitle(lib.getTitle());
      if (name == null || name.isBlank()) {
        continue;
      }
      byName.put(name.toLowerCase(Locale.ROOT), lib);
      uses.put(name.toLowerCase(Locale.ROOT), extractUses(exporter, lib));
    }

    List<String> order = topologicalOrder(byName.keySet(), uses);

    for (int pass = 0; pass < 3; pass++) {
      int before = done.size();
      for (String key : order) {
        ScriptLibrary lib = byName.get(key);
        if (lib == null) {
          continue;
        }
        String title = primaryTitle(lib.getTitle());
        compileOne(lib, "scriptlibrary", title, compiled, skipped, errors, done);
      }
      // Also any lib missing from order (cycle / unnamed edge)
      for (Map.Entry<String, ScriptLibrary> e : byName.entrySet()) {
        if (!order.contains(e.getKey())) {
          String title = primaryTitle(e.getValue().getTitle());
          compileOne(e.getValue(), "scriptlibrary", title, compiled, skipped, errors, done);
        }
      }
      if (done.size() == before) {
        break;
      }
    }
  }

  private static List<String> extractUses(DxlExporter exporter, ScriptLibrary lib) {
    List<String> out = new ArrayList<>();
    try {
      String dxl = exporter.exportDocument(lib.getDocument());
      Matcher m = USE_STMT.matcher(dxl);
      while (m.find()) {
        String dep = m.group(1).trim();
        if (!dep.isEmpty()) {
          out.add(dep.toLowerCase(Locale.ROOT));
        }
      }
    } catch (Exception ignored) {
      // fall through — unordered compile still attempted
    }
    return out;
  }

  /** Kahn-style topo sort; leftover nodes (cycles) appended in name order. */
  private static List<String> topologicalOrder(
      Set<String> nodes, Map<String, List<String>> uses) {
    Map<String, Integer> indegree = new HashMap<>();
    Map<String, List<String>> dependents = new HashMap<>();
    for (String n : nodes) {
      indegree.put(n, 0);
      dependents.put(n, new ArrayList<>());
    }
    for (String n : nodes) {
      for (String dep : uses.getOrDefault(n, List.of())) {
        if (!nodes.contains(dep)) {
          continue; // external / missing
        }
        dependents.get(dep).add(n);
        indegree.put(n, indegree.get(n) + 1);
      }
    }

    List<String> queue = new ArrayList<>();
    for (String n : nodes) {
      if (indegree.get(n) == 0) {
        queue.add(n);
      }
    }
    queue.sort(String.CASE_INSENSITIVE_ORDER);

    List<String> order = new ArrayList<>();
    while (!queue.isEmpty()) {
      String n = queue.remove(0);
      order.add(n);
      List<String> nexts = new ArrayList<>(dependents.get(n));
      nexts.sort(String.CASE_INSENSITIVE_ORDER);
      for (String d : nexts) {
        int v = indegree.get(d) - 1;
        indegree.put(d, v);
        if (v == 0) {
          queue.add(d);
          queue.sort(String.CASE_INSENSITIVE_ORDER);
        }
      }
    }

    List<String> leftover = new ArrayList<>();
    for (String n : nodes) {
      if (!order.contains(n)) {
        leftover.add(n);
      }
    }
    leftover.sort(String.CASE_INSENSITIVE_ORDER);
    order.addAll(leftover);
    return order;
  }

  private static void compileStream(
      Stream<? extends DesignElement> stream,
      String type,
      JsonArray compiled,
      JsonArray skipped,
      JsonArray errors,
      Set<String> done) {
    List<DesignElement> list = new ArrayList<>();
    try {
      var it = stream.iterator();
      while (true) {
        DesignElement el;
        try {
          if (!it.hasNext()) {
            break;
          }
          el = it.next();
        } catch (RuntimeException skippedEl) {
          continue;
        }
        if (el != null) {
          list.add(el);
        }
      }
    } catch (RuntimeException ignored) {
      // e.g. frameset Optional.empty — skip remaining of this type
    }
    for (DesignElement el : list) {
      try {
        compileOne(el, type, titleOf(el), compiled, skipped, errors, done);
      } catch (RuntimeException skippedEl) {
        // keep compiling other elements
      }
    }
  }

  private static void compileOne(
      DesignElement el,
      String type,
      String name,
      JsonArray compiled,
      JsonArray skipped,
      JsonArray errors,
      Set<String> done) {
    String key = type + "|" + (name == null ? "" : name);
    if (done.contains(key)) {
      return;
    }

    JsonObject row = new JsonObject().put("type", type);
    if (name != null && !name.isBlank()) {
      row.put("name", name);
    }
    try {
      Document doc = el.getDocument();
      doc.compileLotusScript();
      doc.save();
      try {
        el.sign();
        el.save();
      } catch (Exception signEx) {
        // Keep compiled code even if re-sign fails (inconsistent field signatures).
      }
      compiled.add(row);
      done.add(key);
      removeRowsWithKey(errors, type, name);
      removeRowsWithKey(skipped, type, name);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      row.put("message", message);
      if (isBenign(message)) {
        skipped.add(row);
        done.add(key); // nothing to retry
      } else {
        // leave out of done so a later pass can retry (Use deps may land)
        // But avoid flooding errors array — only record on final failure.
        // Caller does multiple passes; we replace prior error for same key.
        removeRowsWithKey(errors, type, name);
        errors.add(row);
      }
    }
  }

  private static void removeRowsWithKey(JsonArray arr, String type, String name) {
    for (int i = arr.size() - 1; i >= 0; i--) {
      JsonObject o = arr.getJsonObject(i);
      if (o == null) {
        continue;
      }
      if (!type.equals(o.getString("type"))) {
        continue;
      }
      String n = o.getString("name", "");
      String want = name == null ? "" : name;
      if (n.equals(want)) {
        arr.remove(i);
      }
    }
  }

  private static String titleOf(DesignElement el) {
    if (el instanceof DesignElement.NamedDesignElement named) {
      return primaryTitle(named.getTitle());
    }
    return null;
  }

  /** First segment of {@code Name|Alias}. */
  private static String primaryTitle(String title) {
    if (title == null || title.isBlank()) {
      return title;
    }
    int pipe = title.indexOf('|');
    return (pipe >= 0 ? title.substring(0, pipe) : title).trim();
  }

  private static boolean isBenign(String message) {
    if (message == null || message.isBlank()) {
      return true;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    return lower.contains("no lotusscript")
        || lower.contains("does not contain lotusscript")
        || lower.contains("does not contain any lotusscript")
        || lower.contains("not contain lotusscript")
        || lower.contains("no script")
        || lower.contains("no lotus script")
        || lower.contains("nothing to compile")
        || lower.contains("cannot be compiled")
        || lower.contains("not a lotusscript")
        || lower.contains("7014")
        || lower.contains("7016");
  }
}
