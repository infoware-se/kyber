package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DocumentClass;
import com.hcl.domino.data.IDTable;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Richer Domino blank application chrome from classpath {@code defaults/blank/}: default view,
 * {@code $AppOutline}, framesets, title/outline pages, and Notes launch of {@code $MainFrameset}.
 * Design imports go through {@link DxlImportFixes} (frameset column/row repair, etc.). ACL, Kyber
 * icon, and XPages project are handled elsewhere.
 *
 * <p>{@code createDatabase} leaves a view note with empty {@code $TITLE} that Designer shows as
 * {@code (untitled)}. Kyber then imports the curated {@code All Documents} default view.
 * {@link #removeEmptyTitledViews} deletes those Domino stubs so only the starred curated view
 * remains (and {@code $ViewFrameset} can open it by name).
 */
final class BlankApplication {

  private static final String RESOURCE_DIR = "/defaults/blank/";
  private static final String MAIN_FRAMESET = "$MainFrameset";
  private static final String DEFAULT_VIEW = "All Documents";

  /** classpath file → design type → lookup name */
  private static final String[][] SHELL = {
    {"view_default.dxl", "view", DEFAULT_VIEW},
    {"outline_default.dxl", "outline", "$AppOutline"},
    {"page_apptitle_default.dxl", "page", "$AppTitle"},
    {"page_dbloc_default.dxl", "page", "$DBLocTitle"},
    {"page_outline_default.dxl", "page", "$OutlinePage"},
    {"frameset_view_default.dxl", "frameset", "$ViewFrameset"},
    {"frameset_main_default.dxl", "frameset", MAIN_FRAMESET},
  };

  private BlankApplication() {}

  /** Notes and web open {@code $MainFrameset} when the database is opened. */
  static String notesLaunchFramesetDxl() {
    return "<?xml version='1.0' encoding='utf-8'?>"
        + "<database xmlns='http://www.lotus.com/dxl' version='14.5'>"
        + "<launchsettings>"
        + "<noteslaunch whenopened='openframeset' frameset='"
        + MAIN_FRAMESET
        + "'/>"
        + "<weblaunch whenopened='openframeset' frameset='"
        + MAIN_FRAMESET
        + "'/>"
        + "</launchsettings>"
        + "</database>";
  }

  /**
   * Create missing shell notes and set frameset launch. Safe to call repeatedly; skips titles that
   * already exist.
   *
   * @return {@code ensured}, {@code created[]}, {@code skipped[]}, {@code failed[]}
   */
  static JsonObject ensure(KeepJnxSession session, Database db) throws Exception {
    JsonArray created = new JsonArray();
    JsonArray skipped = new JsonArray();
    JsonArray failed = new JsonArray();
    DxlImporter importer = session.getDxlImporter();

    for (String[] row : SHELL) {
      String resource = row[0];
      String type = row[1];
      String name = row[2];
      if (DesignElementLookup.exists(db, type, name)) {
        skipped.add(name);
        continue;
      }
      try {
        String dxl = DesignDxl.forNewNote(load(resource));
        DesignElementImport.importOne(importer, db, type, dxl, session, DxlDesignPatch.Mode.UPSERT);
        if (!DesignElementLookup.exists(db, type, name)) {
          failed.add(new JsonObject().put("name", name).put("message", "missing after import"));
          continue;
        }
        created.add(name);
      } catch (Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        failed.add(new JsonObject().put("name", name).put("message", message));
      }
    }

    try {
      DxlDesignPatch.importLaunchSettings(importer, notesLaunchFramesetDxl(), db);
      created.add("launchsettings:" + MAIN_FRAMESET);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      failed.add(
          new JsonObject()
              .put("name", "launchsettings:" + MAIN_FRAMESET)
              .put("message", message));
    }

    removeEmptyTitledViews(db, created, failed);

    return new JsonObject()
        .put("ensured", true)
        .put("created", created)
        .put("skipped", skipped)
        .put("failed", failed);
  }

  /**
   * Deletes Domino's auto-created view notes that have a blank {@code $TITLE}. Those notes are
   * invisible to {@code getView("All Documents")} but still appear as a second untitled row in
   * Designer beside the curated default view.
   */
  static void removeEmptyTitledViews(Database db, JsonArray created, JsonArray failed) {
    List<Integer> stubIds = new ArrayList<>();
    try {
      IDTable ids = db.getAllNoteIds(EnumSet.of(DocumentClass.VIEW), false);
      if (ids == null || ids.isEmpty()) {
        return;
      }
      for (int noteId : ids) {
        Document doc = db.getDocumentById(noteId).orElse(null);
        if (doc == null) {
          continue;
        }
        String title = itemText(doc, "$TITLE");
        if (title == null || title.isBlank()) {
          stubIds.add(noteId);
        }
      }
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      failed.add(new JsonObject().put("name", "empty-titled-views").put("message", message));
      return;
    }
    for (int noteId : stubIds) {
      try {
        Document doc = db.getDocumentById(noteId).orElse(null);
        if (doc == null) {
          continue;
        }
        doc.delete();
        created.add("removed-empty-view:" + Integer.toHexString(noteId));
      } catch (Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        failed.add(
            new JsonObject()
                .put("name", "removed-empty-view:" + Integer.toHexString(noteId))
                .put("message", message));
      }
    }
  }

  private static String itemText(Document doc, String name) {
    try {
      List<?> values = doc.getItemValue(name);
      if (values != null && !values.isEmpty() && values.get(0) != null) {
        String text = values.get(0).toString().trim();
        return text.isEmpty() ? null : text;
      }
    } catch (Exception ignored) {
      // missing
    }
    return null;
  }

  private static String load(String resourceFile) throws KeepExceptionWrongDataRequest {
    String path = RESOURCE_DIR + resourceFile;
    try (InputStream in = BlankApplication.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new KeepExceptionWrongDataRequest("Missing classpath resource: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (KeepExceptionWrongDataRequest e) {
      throw e;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionWrongDataRequest("Cannot read " + path + ": " + message);
    }
  }
}
