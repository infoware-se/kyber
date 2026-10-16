package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.NamedFileElement;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Collection JSON. The existing name array stays. {@code elements} adds noteId, and fileName,
 * kind and comment when the note has them.
 */
final class DesignListing {

  private DesignListing() {}

  static JsonObject elements(String key, Stream<? extends DesignElement.NamedDesignElement> elements) {
    return elements(key, elements, null, null);
  }

  static JsonObject elements(
      String key,
      Stream<? extends DesignElement.NamedDesignElement> elements,
      Function<DesignElement.NamedDesignElement, String> kindOf) {
    return elements(key, elements, null, kindOf);
  }

  static JsonObject elements(
      String key,
      Stream<? extends DesignElement.NamedDesignElement> elements,
      Predicate<DesignElement.NamedDesignElement> include,
      Function<DesignElement.NamedDesignElement, String> kindOf) {
    List<JsonObject> rows = new ArrayList<>();
    if (elements != null) {
      var it = elements.iterator();
      while (true) {
        try {
          if (!it.hasNext()) {
            break;
          }
          DesignElement.NamedDesignElement el = it.next();
          if (include != null && !include.test(el)) {
            continue;
          }
          String name = el.getTitle();
          if (name == null || name.isBlank()) {
            continue;
          }
          String kind = null;
          if (kindOf != null) {
            try {
              kind = kindOf.apply(el);
            } catch (RuntimeException ignored) {
              kind = null;
            }
          }
          rows.add(row(name, el, kind));
        } catch (RuntimeException skipped) {
          // JNX can throw while reading one frameset and still return the rest.
        }
      }
    }
    rows.sort(Comparator.comparing(r -> r.getString("name"), String.CASE_INSENSITIVE_ORDER));
    return emit(key, rows);
  }

  static JsonObject named(String key, List<JsonObject> rows) {
    rows.sort(Comparator.comparing(r -> r.getString("name", ""), String.CASE_INSENSITIVE_ORDER));
    return emit(key, rows);
  }

  static JsonObject row(String name, DesignElement el, String kind) {
    JsonObject row = new JsonObject().put("name", name);
    put(row, "noteId", NoteIds.of(documentOf(el)));
    put(row, "comment", commentOf(el));
    if (el instanceof DesignElement.NamedDesignElement named) {
      put(row, "fileName", fileNameOf(named));
      putAliases(row, aliasesOf(named, name));
    }
    put(row, "kind", kind);
    return row;
  }

  static JsonObject row(String name, Document doc) {
    JsonObject row = new JsonObject().put("name", name);
    put(row, "noteId", NoteIds.of(doc));
    if (doc != null) {
      put(row, "fileName", itemText(doc, "$FileNames"));
      put(row, "comment", itemText(doc, "$Comment"));
      putAliases(row, aliasesFromPiped(itemText(doc, "$TITLE"), name));
    }
    return row;
  }

  static JsonObject residual(String key, Database db, String type) {
    List<JsonObject> rows = new ArrayList<>();
    boolean javaKind = "javasource".equalsIgnoreCase(type);
    for (BulkOnlyNotes.Entry entry : BulkOnlyNotes.list(db, java.util.Set.of())) {
      if (!type.equalsIgnoreCase(entry.type())) {
        continue;
      }
      JsonObject row = row(entry.name(), entry.document());
      if (javaKind) {
        try {
          put(row, "kind", JavaSourceKinds.detect(ResidualDesign.exportDxl(db, entry.document())));
        } catch (Exception ignored) {
          // The name still belongs on the list when one note cannot be exported.
        }
      }
      rows.add(row);
    }
    return named(key, rows);
  }

  private static JsonObject emit(String key, List<JsonObject> rows) {
    JsonArray names = new JsonArray();
    JsonArray elements = new JsonArray();
    for (JsonObject row : rows) {
      names.add(row.getString("name"));
      elements.add(row);
    }
    return new JsonObject().put(key, names).put("elements", elements);
  }

  private static JsonArray aliasesOf(DesignElement.NamedDesignElement el, String name) {
    try {
      List<String> aliases = el.getAliases();
      if (aliases == null || aliases.isEmpty()) {
        return null;
      }
      JsonArray out = new JsonArray();
      for (String alias : aliases) {
        if (alias == null || alias.isBlank() || alias.equalsIgnoreCase(name)) {
          continue;
        }
        out.add(alias);
      }
      return out.isEmpty() ? null : out;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  static JsonArray aliasesFromPiped(String value, String name) {
    if (value == null || !value.contains("|")) {
      return null;
    }
    JsonArray out = new JsonArray();
    String[] parts = value.split("\\|", -1);
    for (int i = 1; i < parts.length; i++) {
      String alias = parts[i].trim();
      if (alias.isEmpty() || alias.equalsIgnoreCase(name)) {
        continue;
      }
      out.add(alias);
    }
    return out.isEmpty() ? null : out;
  }

  private static Document documentOf(DesignElement el) {
    try {
      return el.getDocument();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static void putAliases(JsonObject row, JsonArray aliases) {
    if (aliases != null && !aliases.isEmpty()) {
      row.put("aliases", aliases);
    }
  }

  private static String fileNameOf(DesignElement.NamedDesignElement el) {
    if (!(el instanceof NamedFileElement<?> file)) {
      return null;
    }
    try {
      List<String> names = file.getFileNames();
      if (names == null || names.isEmpty() || names.get(0) == null || names.get(0).isBlank()) {
        return null;
      }
      return names.get(0);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String commentOf(DesignElement el) {
    try {
      String comment = el.getComment();
      return comment == null || comment.isBlank() ? null : comment;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String itemText(Document doc, String itemName) {
    try {
      List<?> values = doc.getItemValue(itemName);
      if (values != null && !values.isEmpty() && values.get(0) != null) {
        String text = values.get(0).toString().trim();
        return text.isEmpty() ? null : text;
      }
    } catch (Exception ignored) {
      // missing item
    }
    return null;
  }

  private static void put(JsonObject row, String key, String value) {
    if (value != null && !value.isBlank()) {
      row.put(key, value);
    }
  }
}
