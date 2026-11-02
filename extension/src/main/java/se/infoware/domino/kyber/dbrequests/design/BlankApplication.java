package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Richer Domino blank application chrome from classpath {@code defaults/blank/}: default view,
 * {@code $AppOutline}, framesets, title/outline pages, and Notes launch of {@code $MainFrameset}.
 * Design imports go through {@link DxlImportFixes} (frameset column/row repair, etc.). ACL, Kyber
 * icon, and XPages project are handled elsewhere.
 */
final class BlankApplication {

  private static final String RESOURCE_DIR = "/defaults/blank/";
  private static final String MAIN_FRAMESET = "$MainFrameset";

  /** classpath file → design type → lookup name */
  private static final String[][] SHELL = {
    {"view-default.dxl", "view", "(untitled)"},
    {"outline-AppOutline.dxl", "outline", "$AppOutline"},
    {"page-AppTitle.dxl", "page", "$AppTitle"},
    {"page-DBLocTitle.dxl", "page", "$DBLocTitle"},
    {"page-OutlinePage.dxl", "page", "$OutlinePage"},
    {"frameset-ViewFrameset.dxl", "frameset", "$ViewFrameset"},
    {"frameset-MainFrameset.dxl", "frameset", MAIN_FRAMESET},
  };

  private BlankApplication() {}

  /** Notes client opens {@code $MainFrameset} when the database is opened. */
  static String notesLaunchFramesetDxl() {
    return "<?xml version='1.0' encoding='utf-8'?>"
        + "<database xmlns='http://www.lotus.com/dxl' version='14.5'>"
        + "<launchsettings>"
        + "<noteslaunch whenopened='openframeset' frameset='"
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

    return new JsonObject()
        .put("ensured", true)
        .put("created", created)
        .put("skipped", skipped)
        .put("failed", failed);
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
