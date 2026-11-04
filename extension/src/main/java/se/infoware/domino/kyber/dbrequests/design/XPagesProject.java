package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.infoware.domino.kyber.KyberLog;

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

  private static final Logger LOG = KyberLog.of(XPagesProject.class);

  private static final String PLUGIN_XML = "plugin.xml";
  private static final String CLASSPATH = ".classpath";
  private static final String RESOURCE_DIR = "/defaults/xpages-project/";
  private static final Pattern FILE_DATA =
      Pattern.compile(
          "(?is)<item\\s+name=['\"]\\$?FileData['\"][^>]*>\\s*<rawitemdata[^>]*>\\s*([A-Za-z0-9+/=\\s]+)\\s*</rawitemdata>");

  /**
   * Skeleton entries: classpath resource file → design type → $TITLE.
   *
   * <p>Encoding prefs first so Designer does not warn "no explicit encoding" even when {@code
   * .project} or the activator class note fail to import (invalid CDFILE structure on some
   * Domino builds). {@code Local/plugin/Activator.java} is stored for when Designer creates its
   * temp {@code Local/} scratch; {@code .classpath} must not list {@code Local/} (not
   * ODP-persisted — causes "missing required source folder: Local"). {@code plugin.xml} omits
   * {@code class="plugin.Activator"} so PDE does not require that folder.
   */
  private static final String[][] SKELETON = {
    {"resources-prefs.dxl", "xpagesresource", ".settings/org.eclipse.core.resources.prefs"},
    {"project.dxl", "xpagesresource", ".project"},
    {"activator-java.dxl", "xpagesresource", "Local/plugin/Activator.java"},
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
   * Create missing skeleton files (create-if-absent), then heal Designer pollution: duplicate
   * {@code plugin.xml}/{@code .classpath}, a {@code Local/} source entry, or {@code
   * class="plugin.Activator"}. Continues when a single skeleton note fails; encoding prefs must
   * succeed.
   *
   * @return {@code ensured}, {@code created[]}, {@code skipped[]}, {@code failed[]}, {@code
   *     repaired[]}
   */
  static JsonObject ensure(KeepJnxSession session, Database db) throws Exception {
    JsonArray created = new JsonArray();
    JsonArray skipped = new JsonArray();
    JsonArray failed = new JsonArray();
    JsonArray repaired = new JsonArray();
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

    dedupeNamed(db, CLASSPATH, repaired, failed);
    dedupeNamed(db, PLUGIN_XML, repaired, failed);
    healClasspath(session, db, importer, repaired, failed);
    healPluginXml(session, db, importer, repaired, failed);

    if (!exists(db, "xpagesresource", ENCODING_PREFS)) {
      throw new KeepExceptionWrongDataRequest(
          "XPages project encoding prefs missing after ensure: " + ENCODING_PREFS);
    }

    KyberLog.info(
        LOG,
        "XPages ensure nsf="
            + KyberLog.nsf(db)
            + " project="
            + projectName
            + " created="
            + created
            + " skipped="
            + skipped.size()
            + " repaired="
            + repaired
            + " failed="
            + failed);
    if (!failed.isEmpty()) {
      KyberLog.warn(LOG, "XPages ensure failures nsf=" + KyberLog.nsf(db) + " failed=" + failed);
    }

    return new JsonObject()
        .put("ensured", true)
        .put("projectName", projectName)
        .put("created", created)
        .put("skipped", skipped)
        .put("failed", failed)
        .put("repaired", repaired);
  }

  /** Designer temp folder — must not appear as a source entry in stored {@code .classpath}. */
  static boolean classpathPayloadNeedsRepair(String payload) {
    if (payload == null || payload.isBlank()) {
      return false;
    }
    return payload.contains("path=\"Local") || payload.contains("path='Local");
  }

  /** PDE Activator class forces a {@code Local/} source folder that Designer does not persist. */
  static boolean pluginXmlPayloadNeedsRepair(String payload) {
    if (payload == null || payload.isBlank()) {
      return false;
    }
    return payload.contains("class=\"plugin.Activator\"")
        || payload.contains("class='plugin.Activator'");
  }

  private static void dedupeNamed(
      Database db, String title, JsonArray repaired, JsonArray failed) {
    try {
      List<BulkOnlyNotes.Entry> matches = findAllNamed(db, title);
      if (matches.size() <= 1) {
        return;
      }
      matches.sort(Comparator.comparingInt(e -> noteIdOrdinal(e.document())));
      for (int i = 1; i < matches.size(); i++) {
        matches.get(i).document().delete();
      }
      repaired.add(title + " (deduped)");
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      failed.add(new JsonObject().put("title", title).put("message", "dedupe: " + message));
    }
  }

  private static void healClasspath(
      KeepJnxSession session,
      Database db,
      DxlImporter importer,
      JsonArray repaired,
      JsonArray failed) {
    healNamed(
        session,
        db,
        importer,
        "classpath.dxl",
        CLASSPATH,
        XPagesProject::classpathPayloadNeedsRepair,
        repaired,
        failed);
  }

  private static void healPluginXml(
      KeepJnxSession session,
      Database db,
      DxlImporter importer,
      JsonArray repaired,
      JsonArray failed) {
    healNamed(
        session,
        db,
        importer,
        "plugin-xml.dxl",
        PLUGIN_XML,
        XPagesProject::pluginXmlPayloadNeedsRepair,
        repaired,
        failed);
  }

  private static void healNamed(
      KeepJnxSession session,
      Database db,
      DxlImporter importer,
      String resource,
      String title,
      java.util.function.Predicate<String> needsRepair,
      JsonArray repaired,
      JsonArray failed) {
    try {
      List<BulkOnlyNotes.Entry> matches = findAllNamed(db, title);
      if (matches.isEmpty()) {
        return;
      }
      String payload = readFilePayload(db, matches.get(0).document());
      if (!needsRepair.test(payload)) {
        return;
      }
      for (BulkOnlyNotes.Entry entry : matches) {
        entry.document().delete();
      }
      String dxl = DesignDxl.forNewNote(loadSkeletonDxl(resource));
      DesignElementImport.importOne(importer, db, "xpagesresource", dxl, session, DxlDesignPatch.Mode.UPSERT);
      if (!exists(db, "xpagesresource", title)) {
        failed.add(new JsonObject().put("title", title).put("message", "missing after repair"));
        return;
      }
      repaired.add(title);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      failed.add(new JsonObject().put("title", title).put("message", "repair: " + message));
    }
  }

  private static List<BulkOnlyNotes.Entry> findAllNamed(Database db, String title) {
    String wanted = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    List<BulkOnlyNotes.Entry> out = new ArrayList<>();
    for (BulkOnlyNotes.Entry e : BulkOnlyNotes.list(db, java.util.Set.of())) {
      if ("xpagesresource".equalsIgnoreCase(e.type()) && e.name().equalsIgnoreCase(wanted)) {
        out.add(e);
      }
    }
    return out;
  }

  private static int noteIdOrdinal(Document doc) {
    try {
      return doc.getNoteID();
    } catch (Exception e) {
      return Integer.MAX_VALUE;
    }
  }

  private static String readFilePayload(Database db, Document doc) throws Exception {
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(doc);
    Matcher matcher = FILE_DATA.matcher(dxl);
    if (!matcher.find()) {
      return "";
    }
    byte[] raw = Base64.getMimeDecoder().decode(matcher.group(1).replaceAll("\\s+", ""));
    if (raw.length <= 42) {
      return "";
    }
    return new String(raw, 42, raw.length - 42, StandardCharsets.UTF_8);
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
