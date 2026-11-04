package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import se.infoware.domino.kyber.KyberLog;

/**
 * One-element create-or-replace for an existing Kyber-enabled NSF. Used by {@code PUT /upsert}.
 * Does not create databases, run bulk {@code PUT /import}, or {@code compileAll}.
 */
final class DesignUpsert {

  private static final Logger LOG = KyberLog.of(DesignUpsert.class);
  private static final Pattern NOTEINFO =
      Pattern.compile("(?is)<noteinfo\\b[^>]*?(?:/>|>.*?</noteinfo>)");

  private DesignUpsert() {}

  static JsonObject upsert(KeepJnxSession session, Database db, JsonObject incoming)
      throws Exception {
    JsonObject in = unwrapBody(incoming);
    String typeRaw = blankToNull(in.getString("type"));
    if (typeRaw == null) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    String t = DesignBulkTypes.normalize(typeRaw);
    if (!DesignBulkTypes.isKnown(t) && !"dbicon".equals(t)) {
      throw new KeepExceptionWrongDataRequest("Unknown design element type: " + typeRaw);
    }
    String dxl = in.getString("dxl", "");
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl is required");
    }
    String nameHint = RequestParams.optionalName(in);
    if (nameHint != null
        && !DesignBulkTypes.isSingleton(t)
        && !"acl".equals(t)
        && !"dbicon".equals(t)) {
      String fromDxl = DesignDxl.designName(dxl);
      if (fromDxl != null && !nameHint.equalsIgnoreCase(fromDxl)) {
        dxl = DesignDxl.withName(dxl, nameHint, rootHints(t).split(","));
      }
    }
    JsonObject check = new JsonObject().put("type", t).put("dxl", dxl);
    if (nameHint != null) {
      check.put("name", nameHint);
    }
    DesignImportCheck.check(check);

    String name = resolveName(t, dxl, nameHint);
    boolean existed =
        DesignBulkTypes.isSingleton(t) || "dbicon".equals(t) || "acl".equals(t)
            ? DesignElementLookup.exists(db, t, name)
            : name != null && DesignElementLookup.exists(db, t, name);

    JsonObject xpagesProject = null;
    String toImport;
    DxlDesignPatch.Mode mode;
    if (existed) {
      String existing =
          DesignElementLookup.exportDxl(db, t, name).orElse(null);
      toImport = preserveIdentity(dxl, existing);
      mode = DxlDesignPatch.Mode.REPLACE;
      // ACL / some singletons replace via UPSERT path inside importOne; REPLACE is fine when
      // noteinfo is present. Fall back to UPSERT when identity could not be preserved.
      if (existing != null && NoteIds.unidFromDxl(toImport) == null && !"acl".equals(t)) {
        mode = DxlDesignPatch.Mode.UPSERT;
      }
    } else {
      toImport = DesignDxl.forNewNote(dxl);
      mode = DxlDesignPatch.Mode.UPSERT;
      xpagesProject = XPagesProject.ensureIfNeeded(session, db, t, in.getString("kind"));
    }

    DxlImporter importer = session.getDxlImporter();
    JsonObject imported =
        DesignElementImport.importOne(importer, db, t, toImport, session, mode, name);
    String exported =
        DesignElementLookup.exportDxl(db, t, name).orElse(toImport);

    JsonObject out =
        new JsonObject()
            .put("success", true)
            .put("created", !existed)
            .put("type", t)
            .put("dxl", exported);
    if (name != null && !name.isBlank()) {
      out.put("name", name);
    }
    if (xpagesProject != null) {
      out.put("xpagesProject", xpagesProject);
    }
    if (imported.containsKey("signWarning")) {
      out.put("signWarning", imported.getString("signWarning"));
    }
    DesignPayload.enrich(out, exported);
    KyberLog.info(
        LOG,
        "upsert "
            + (existed ? "replace" : "create")
            + " type="
            + t
            + (name == null ? "" : " name=" + name)
            + " nsf="
            + KyberLog.nsf(db));
    return out;
  }

  /** Package-visible for unit tests — keep target note identity when replacing. */
  static String preserveIdentity(String incoming, String existing) throws KeepExceptionWrongDataRequest {
    if (incoming == null || incoming.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl is required");
    }
    if (existing == null || existing.isBlank()) {
      return DesignDxl.forNewNote(incoming);
    }
    Matcher ni = NOTEINFO.matcher(existing);
    if (!ni.find()) {
      return DesignDxl.forNewNote(incoming);
    }
    String noteinfo = ni.group();
    String stripped = DesignDxl.forNewNote(incoming);
    // If client already carries the same UNID, keep their payload (minus replicaid only).
    String incomingUnid = NoteIds.unidFromDxl(incoming);
    String existingUnid = NoteIds.unidFromDxl(existing);
    if (incomingUnid != null
        && existingUnid != null
        && incomingUnid.equalsIgnoreCase(existingUnid)) {
      return DesignDxl.withoutReplicaId(incoming);
    }
    int gt = indexOfRootClose(stripped);
    if (gt < 0) {
      return DesignDxl.withoutReplicaId(incoming);
    }
    return stripped.substring(0, gt + 1) + noteinfo + stripped.substring(gt + 1);
  }

  private static int indexOfRootClose(String dxl) {
    // First tag close after optional XML declaration / DOCTYPE.
    int i = 0;
    while (i < dxl.length() && Character.isWhitespace(dxl.charAt(i))) {
      i++;
    }
    if (dxl.startsWith("<?", i)) {
      int end = dxl.indexOf("?>", i);
      if (end < 0) {
        return -1;
      }
      i = end + 2;
    }
    while (i < dxl.length() && Character.isWhitespace(dxl.charAt(i))) {
      i++;
    }
    if (dxl.startsWith("<!", i)) {
      int end = dxl.indexOf('>', i);
      if (end < 0) {
        return -1;
      }
      i = end + 1;
    }
    while (i < dxl.length() && Character.isWhitespace(dxl.charAt(i))) {
      i++;
    }
    if (i >= dxl.length() || dxl.charAt(i) != '<') {
      return -1;
    }
    return dxl.indexOf('>', i);
  }

  private static String resolveName(String type, String dxl, String nameHint)
      throws KeepExceptionWrongDataRequest {
    if (DesignBulkTypes.isSingleton(type) || "acl".equals(type)) {
      return nameHint;
    }
    if ("dbicon".equals(type)) {
      return DesignElementLookup.DBICON_NAME;
    }
    if (nameHint != null && !nameHint.isBlank()) {
      return nameHint.trim();
    }
    String fromDxl = DesignDxl.designName(dxl);
    if (fromDxl == null || fromDxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required for type " + type);
    }
    return fromDxl;
  }

  static JsonObject unwrapBody(JsonObject incoming) {
    if (incoming == null) {
      return new JsonObject();
    }
    // KEEP may nest a JSON object under body; text/xml leaves a Buffer there — ignore non-JSON.
    Object raw = incoming.getValue("body");
    if (!(raw instanceof JsonObject body)) {
      return incoming;
    }
    JsonObject merged = incoming.copy();
    for (String key : body.fieldNames()) {
      if (!merged.containsKey(key) || isBlank(merged.getValue(key))) {
        merged.put(key, body.getValue(key));
      }
    }
    return merged;
  }

  private static boolean isBlank(Object v) {
    return v == null || (v instanceof String s && s.isBlank());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String rootHints(String type) {
    return switch (DesignBulkTypes.normalize(type)) {
      case "form" -> "form";
      case "view" -> "view";
      case "folder" -> "folder";
      case "page" -> "page";
      case "subform" -> "subform";
      case "outline" -> "outline";
      case "frameset" -> "frameset";
      case "agent" -> "agent";
      case "scriptlibrary" -> "scriptlibrary";
      case "image", "dbicon" -> "imageresource";
      case "fileresource" -> "fileresource,note";
      case "stylesheet" -> "stylesheet,note,fileresource";
      case "theme" -> "theme,note,fileresource";
      case "sharedfield" -> "sharedfield";
      case "sharedcolumn" -> "sharedcolumn";
      case "sharedactions" -> "sharedactions";
      case "navigator" -> "navigator,note";
      case "jar", "applet", "javasource", "webcontent", "xpagesresource", "designnote" ->
          "note,fileresource,appletresource";
      case "dataconnection" -> "dataconnection,note,fileresource";
      case "webservice", "webserviceconsumer" -> "webservice,note,agent";
      case "customcontrol", "xpage" -> "note";
      case "wiringproperties", "compositeapplication", "compositecomponent" -> "note,fileresource";
      default -> "note,fileresource,form,view,agent";
    };
  }
}
