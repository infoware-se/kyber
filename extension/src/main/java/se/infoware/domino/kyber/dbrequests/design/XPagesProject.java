package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal XPages application skeleton ({@code xpagesresource} + {@code xsp.properties}) so an NSF
 * without prior XPages can host a runnable first page or build Java design elements.
 *
 * <p>Applied only when needed: first {@code xpage}, {@code javasource}, or Java {@code agent} /
 * {@code scriptlibrary} — not on empty {@code POST /database}.
 *
 * <p>Skeleton DXL is copied from a known-good Designer export with only {@code noteinfo}/{@code
 * replicaid} stripped ({@link DesignDxl#forNewNote}). FileData payloads are not rewritten — Domino
 * CDFILE headers are length-sensitive.
 */
final class XPagesProject {

  private static final String PLUGIN_XML = "plugin.xml";
  private static final String RESOURCE_DIR = "/defaults/xpages-project/";

  /**
   * Skeleton entries: classpath resource file → design type → $TITLE.
   *
   * <p>Encoding prefs first so Designer does not warn "no explicit encoding" even when {@code
   * .project} or the activator class note fail to import (invalid CDFILE structure on some
   * Domino builds).
   */
  private static final String[][] SKELETON = {
    {"resources-prefs.dxl", "xpagesresource", ".settings/org.eclipse.core.resources.prefs"},
    {"project.dxl", "xpagesresource", ".project"},
    {"classpath.dxl", "xpagesresource", ".classpath"},
    {"plugin-xml.dxl", "xpagesresource", PLUGIN_XML},
    {"manifest-mf.dxl", "xpagesresource", "META-INF/MANIFEST.MF"},
    {"build-properties.dxl", "xpagesresource", "build.properties"},
    {"faces-config.dxl", "xpagesresource", "WEB-INF/faces-config.xml"},
    {"activator-class.dxl", "xpagesresource", "WEB-INF/classes/plugin/Activator.class"},
    {"xsp-properties.dxl", "xspproperties", "WEB-INF/xsp.properties"},
  };

  private static final String ENCODING_PREFS = ".settings/org.eclipse.core.resources.prefs";

  private XPagesProject() {}

  static boolean needsEnsure(Database db) throws Exception {
    return BulkOnlyNotes.find(db, "xpagesresource", PLUGIN_XML).isEmpty()
        || BulkOnlyNotes.find(db, "xpagesresource", ENCODING_PREFS).isEmpty();
  }

  /**
   * Whether creating this design type/kind needs the XPages OSGi project (Designer Java build +
   * XPages runtime). LotusScript / Formula / JS agents and libraries do not.
   */
  static boolean requiredFor(String type, String kind) {
    String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    if ("xpage".equals(t) || "javasource".equals(t)) {
      return true;
    }
    String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
    if ("agent".equals(t)) {
      return AgentKinds.JAVA.equals(k) || AgentKinds.IMPORTEDJAVA.equals(k);
    }
    if ("scriptlibrary".equals(t)) {
      return ScriptLibraryKinds.JAVA.equals(k);
    }
    return false;
  }

  /** True when a bulk import payload contains an element that needs the skeleton. */
  static boolean requiredForElements(JsonArray elements) {
    if (elements == null || elements.isEmpty()) {
      return false;
    }
    for (int i = 0; i < elements.size(); i++) {
      JsonObject el = elements.getJsonObject(i);
      if (el == null || el.getBoolean("delete", false)) {
        continue;
      }
      String type = DesignBulkTypes.normalize(el.getString("type", ""));
      String dxl = el.getString("dxl", "");
      String kind = null;
      if ("agent".equals(type)) {
        kind = AgentKinds.detect(dxl);
      } else if ("scriptlibrary".equals(type)) {
        kind = ScriptLibraryKinds.detect(dxl);
      }
      if (requiredFor(type, kind)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@link #ensure} when {@link #requiredFor(String, String)}; otherwise {@code null}.
   */
  static JsonObject ensureIfNeeded(
      KeepJnxSession session, Database db, String type, String kind) throws Exception {
    if (!requiredFor(type, kind)) {
      return null;
    }
    return ensure(session, db);
  }

  /**
   * Create missing skeleton files (create-if-absent). Always safe to call; skips existing titles.
   * Continues when a single skeleton note fails to import; encoding prefs must succeed.
   *
   * @return {@code ensured}, {@code created[]}, {@code skipped[]}, {@code failed[]}
   */
  static JsonObject ensure(KeepJnxSession session, Database db) throws Exception {
    JsonArray created = new JsonArray();
    JsonArray skipped = new JsonArray();
    JsonArray failed = new JsonArray();
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
      try {
        String dxl = DesignDxl.forNewNote(loadSkeletonDxl(resource));
        DesignElementImport.importOne(importer, db, type, dxl, session, DxlDesignPatch.Mode.UPSERT);
        if (!exists(db, type, title)) {
          failed.add(new JsonObject().put("title", title).put("message", "missing after import"));
          continue;
        }
        created.add(title);
      } catch (Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        failed.add(new JsonObject().put("title", title).put("message", message));
      }
    }

    if (!exists(db, "xpagesresource", ENCODING_PREFS)) {
      throw new KeepExceptionWrongDataRequest(
          "XPages project encoding prefs missing after ensure: " + ENCODING_PREFS);
    }

    return new JsonObject()
        .put("ensured", true)
        .put("projectName", projectName)
        .put("created", created)
        .put("skipped", skipped)
        .put("failed", failed);
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
