package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.design.SharedActions;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Shared Actions design notes are addressable by {@code $TITLE} (preferred) or {@code
 * $$ScriptName} (often {@code $ACTIONS}). JNX {@code getSharedActions()} only returns {@code
 * findFirst()}; this helper lists and resolves by name.
 */
final class SharedActionsNotes {

  private SharedActionsNotes() {}

  static Stream<SharedActions> stream(Database db) {
    return db.getDesign().getDesignElements(SharedActions.class);
  }

  static List<SharedActions> list(Database db) {
    List<SharedActions> out = new ArrayList<>();
    stream(db)
        .filter(el -> nameOf(el) != null)
        .sorted(Comparator.comparing(SharedActionsNotes::nameOf, String.CASE_INSENSITIVE_ORDER))
        .forEach(out::add);
    return out;
  }

  static Optional<SharedActions> find(Database db, String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String wanted = name.trim().toLowerCase(Locale.ROOT);
    return stream(db)
        .filter(
            el -> {
              String n = nameOf(el);
              return n != null && n.toLowerCase(Locale.ROOT).equals(wanted);
            })
        .findFirst();
  }

  static SharedActions require(Database db, String name) throws KeepExceptionWrongDataRequest {
    return find(db, name)
        .orElseThrow(
            () -> new KeepExceptionWrongDataRequest("SharedActions not found: " + name));
  }

  /** Design name: {@code $TITLE} first segment, else {@code $$ScriptName}. */
  static String nameOf(SharedActions el) {
    if (el == null || el.getDocument() == null) {
      return null;
    }
    return nameOf(el.getDocument());
  }

  static String nameOf(Document doc) {
    String title = itemText(doc, "$TITLE");
    if (title == null || title.isBlank()) {
      title = itemText(doc, "$Title");
    }
    if (title != null && !title.isBlank()) {
      int pipe = title.indexOf('|');
      return (pipe >= 0 ? title.substring(0, pipe) : title).trim();
    }
    String script = itemText(doc, "$$ScriptName");
    if (script != null && !script.isBlank()) {
      return script.trim();
    }
    return null;
  }

  private static String itemText(Document doc, String itemName) {
    try {
      List<?> values = doc.getItemValue(itemName);
      if (values != null && !values.isEmpty() && values.get(0) != null) {
        String text = values.get(0).toString().trim();
        return text.isEmpty() ? null : text;
      }
    } catch (Exception ignored) {
      // missing
    }
    return null;
  }
}
