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
 * Minimal XPages application skeleton ({@code xpagesresource} + {@code xsp.properties}) so an NSF
 * without prior XPages can host a runnable first page.
 *
 * <p>Skeleton DXL is copied from a known-good Designer export with only {@code noteinfo}/{@code
 * replicaid} stripped ({@link DesignDxl#forNewNote}). FileData payloads are not rewritten — Domino
 * CDFILE headers are length-sensitive.
 */
final class XPagesProject {

  private static final String PLUGIN_XML = "plugin.xml";
  private static final String RESOURCE_DIR = "/defaults/xpages-project/";

  /** Skeleton entries: classpath resource file → design type → $TITLE. */
  private static final String[][] SKELETON = {
    {"project.dxl", "xpagesresource", ".project"},
    {"classpath.dxl", "xpagesresource", ".classpath"},
    {"resources-prefs.dxl", "xpagesresource", ".settings/org.eclipse.core.resources.prefs"},
    {"plugin-xml.dxl", "xpagesresource", PLUGIN_XML},
    {"build-properties.dxl", "xpagesresource", "build.properties"},
    {"faces-config.dxl", "xpagesresource", "WEB-INF/faces-config.xml"},
    {"activator-class.dxl", "xpagesresource", "WEB-INF/classes/plugin/Activator.class"},
    {"xsp-properties.dxl", "xspproperties", "WEB-INF/xsp.properties"},
  };

  private XPagesProject() {}

  static boolean needsEnsure(Database db) throws Exception {
    return BulkOnlyNotes.find(db, "xpagesresource", PLUGIN_XML).isEmpty();
  }

  /**
   * Create missing skeleton files (create-if-absent). Always safe to call; skips existing titles.
   *
   * @return {@code ensured}, {@code created[]}, {@code skipped[]}
   */
  static JsonObject ensure(KeepJnxSession session, Database db) throws Exception {
    JsonArray created = new JsonArray();
    JsonArray skipped = new JsonArray();
    String projectName = projectNameFromNsf(db);
    DxlImporter importer = session.getDxlImporter();

    for (String[] row : SKELETON) {
      String resource = row[0];
      String type = row[1];
      String title = row[2];
      if (exists(db, type, title)) {
        skipped.add(title);
        continue;
      }
      String dxl = DesignDxl.forNewNote(loadSkeletonDxl(resource));
      DesignElementImport.importOne(importer, db, type, dxl, session, DxlDesignPatch.Mode.UPSERT);
      if (!exists(db, type, title)) {
        throw new KeepExceptionWrongDataRequest(
            "XPages project file missing after import: " + title);
      }
      created.add(title);
    }

    return new JsonObject()
        .put("ensured", true)
        .put("projectName", projectName)
        .put("created", created)
        .put("skipped", skipped);
  }

  private static boolean exists(Database db, String type, String title) throws Exception {
    if ("xspproperties".equals(type)) {
      return DesignElementLookup.exists(db, "xspproperties", null);
    }
    return BulkOnlyNotes.find(db, type, title).isPresent();
  }

  private static String loadSkeletonDxl(String resourceFile) throws KeepExceptionWrongDataRequest {
    String path = RESOURCE_DIR + resourceFile;
    try (InputStream in = XPagesProject.class.getResourceAsStream(path)) {
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

  static String projectNameFromNsf(Database db) {
    try {
      String path = db.getAbsoluteFilePath();
      if (path == null || path.isBlank()) {
        path = db.getRelativeFilePath();
      }
      if (path == null || path.isBlank()) {
        return "xpagesapp";
      }
      path = path.replace('\\', '/');
      int slash = path.lastIndexOf('/');
      String base = slash >= 0 ? path.substring(slash + 1) : path;
      return base.isBlank() ? "xpagesapp" : base;
    } catch (Exception e) {
      return "xpagesapp";
    }
  }
}
