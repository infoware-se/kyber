package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;

/** Copy / rename orchestration for design notes. */
final class DesignCopyRename {

  private DesignCopyRename() {}

  static JsonObject copy(
      KeepJnxSession session,
      Database sourceDb,
      String type,
      String name,
      Database targetDb,
      String toName)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    if (toName == null || toName.isBlank()) {
      throw new KeepExceptionWrongDataRequest("toName is required");
    }
    String from = name.trim();
    String dest = toName.trim();
    String dxl =
        DesignElementLookup.exportDxl(sourceDb, t, from)
            .orElseThrow(
                () -> new KeepExceptionNotFound(t + " not found: " + from));
    String rootsHint = rootHints(t);
    String renamed = DesignDxl.withName(DesignDxl.forNewNote(dxl), dest, rootsHint.split(","));
    DesignCrud.conflictIfExists(targetDb, t, dest);
    DxlImporter importer = session.getDxlImporter();
    DesignElementImport.importOne(
        importer, targetDb, t, renamed, session, DxlDesignPatch.Mode.CREATE, dest);
    String after =
        DesignElementLookup.exportDxl(targetDb, t, dest)
            .orElseThrow(
                () -> new KeepExceptionServer("%s missing after copy: %s", t, dest));
    JsonObject out =
        new JsonObject()
            .put("success", true)
            .put("type", t)
            .put("name", dest)
            .put("fromName", from)
            .put("dxl", after);
    DesignPayload.enrich(out, after);
    return out;
  }

  static JsonObject rename(
      KeepJnxSession session, Database db, String type, String name, String newName)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    if (newName == null || newName.isBlank()) {
      throw new KeepExceptionWrongDataRequest("newName is required");
    }
    String from = name.trim();
    String dest = newName.trim();
    if (from.equalsIgnoreCase(dest)) {
      throw new KeepExceptionWrongDataRequest("newName must differ from name");
    }
    DesignCrud.notFoundIfMissing(db, t, from);
    DesignCrud.conflictIfExists(db, t, dest);
    String dxl =
        DesignElementLookup.exportDxl(db, t, from)
            .orElseThrow(
                () -> new KeepExceptionNotFound(t + " not found: " + from));
    String beforeUnid = NoteIds.unidFromDxl(dxl);
    String rootsHint = rootHints(t);
    String renamed = DesignDxl.withName(dxl, dest, rootsHint.split(","));
    DxlImporter importer = session.getDxlImporter();
    // REPLACE by UNID in DXL noteinfo keeps identity when Domino allows it.
    DesignElementImport.importOne(
        importer, db, t, renamed, session, DxlDesignPatch.Mode.REPLACE, dest);
    String after =
        DesignElementLookup.exportDxl(db, t, dest)
            .orElseThrow(
                () -> new KeepExceptionServer("%s missing after rename: %s", t, dest));
    String afterUnid = NoteIds.unidFromDxl(after);
    JsonObject out =
        new JsonObject()
            .put("success", true)
            .put("type", t)
            .put("name", dest)
            .put("fromName", from)
            .put(
                "unidPreserved",
                beforeUnid != null && beforeUnid.equalsIgnoreCase(afterUnid))
            .put("dxl", after);
    DesignPayload.enrich(out, after);
    return out;
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
