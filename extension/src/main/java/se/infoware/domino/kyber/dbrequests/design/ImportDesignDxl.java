package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Bulk import design DXL - PUT /designdxl?nsfPath=&dbTitle=
 *
 * <p>HTTP handler remaps query {@code nsfPath} (target) to {@code targetNsfPath} and opens
 * {@code KeepConfig.nsf} as the KEEP connection DB so a missing NSF can be created first.
 *
 * <p>This is the only endpoint that may create an NSF. Per-type CRUD requires an existing database.
 * The API never deletes databases.
 *
 * <p>Optional query {@code dbTitle} sets the database title when the NSF is created (ignored if
 * the NSF already exists). Do not use {@code title} — KEEP may populate that from the bootstrap DB.
 *
 * <p>On create, ensures LocalDomainAdmins / LocalDomainServers as Manager (add or overwrite).
 * When the payload includes {@code type=acl}, that ACL DXL fully replaces the database ACL
 * (overwrite/drop), then LocalDomain* are ensured as Manager again.
 *
 * <p>Elements may set {@code "delete": true} with {@code name} and/or {@code unid} to remove a
 * design note (bulk-only cleanup) instead of importing DXL.
 */
@MinimumAcl(AclLevel.MANAGER)
public class ImportDesignDxl extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String target =
        firstNonBlank(
            request.incoming.getString("targetNsfPath"),
            request.incoming.getString("nsfPath"));
    if (target == null || target.isBlank()) {
      throw new KeepExceptionWrongDataRequest("nsfPath (target) is required");
    }
    target = target.trim();

    // Prefer dbTitle — KEEP request params often already contain "title" (bootstrap NSF title).
    String title =
        firstNonBlank(request.incoming.getString("dbTitle"), request.incoming.getString("databaseTitle"));
    if (title != null) {
      title = title.trim();
      if (title.isEmpty()) {
        title = null;
      }
    }

    JsonObject payload = request.incoming.getJsonObject("body", request.incoming);
    JsonArray elements = payload.getJsonArray("elements");
    if (elements == null) {
      elements = request.incoming.getJsonArray("elements");
    }
    if (elements == null) {
      throw new KeepExceptionWrongDataRequest("elements array is required");
    }

    boolean hasAcl = containsType(elements, "acl");

    KeepJnxSession session = request.session();
    boolean created = openOrCreate(session, target);

    Database db = session.getDatabaseDomino(target);
    if (created && title != null) {
      db.setTitle(title);
    }
    DxlImporter importer = session.getDxlImporter();

    List<JsonObject> ordered = DesignElementIO.sortForImport(elements);
    JsonArray results = new JsonArray();
    int ok = 0;
    int failed = 0;
    String aclError = null;
    for (JsonObject el : ordered) {
      String type = el.getString("type", "");
      String dxl = el.getString("dxl", "");
      boolean isAcl = "acl".equalsIgnoreCase(DesignBulkTypes.normalize(type));
      boolean delete = el.getBoolean("delete", false);
      try {
        JsonObject one;
        if (delete) {
          if (isAcl) {
            throw new KeepExceptionWrongDataRequest("Cannot delete ACL via designdxl delete flag");
          }
          one =
              DesignElementIO.deleteOne(
                  db, type, el.getString("name"), el.getString("unid"));
        } else {
          one = DesignElementIO.importOne(importer, db, type, dxl, session);
        }
        results.add(one);
        ok++;
      } catch (Exception e) {
        failed++;
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (isAcl) {
          aclError = message;
        }
        JsonObject row =
            new JsonObject()
                .put("success", false)
                .put("type", type)
                .put("message", message);
        if (el.getString("name") != null) {
          row.put("name", el.getString("name"));
        }
        results.add(row);
        // ACL in payload must be applied; stop so we do not leave a half-imported ACL state unclear
        if (isAcl) {
          break;
        }
      }
    }

    if (hasAcl && aclError != null) {
      throw new KeepExceptionWrongDataRequest("ACL import failed: " + aclError);
    }
    if (hasAcl && !resultsContainSuccessfulAcl(results)) {
      throw new KeepExceptionWrongDataRequest(
          "ACL was present in the import payload but was not applied successfully");
    }

    JsonObject compile = DesignLotusScriptCompile.compileAll(db);

    JsonObject out =
        new JsonObject()
            .put("success", failed == 0)
            .put("created", created)
            .put("nsfPath", target)
            .put("imported", ok)
            .put("failed", failed)
            .put("aclApplied", hasAcl)
            .put("results", results)
            .put("lotusscript", compile);
    if (created && title != null) {
      out.put("dbTitle", title);
    }
    request.emit(out);
  }

  private static boolean containsType(JsonArray elements, String type) {
    String want = DesignBulkTypes.normalize(type);
    for (int i = 0; i < elements.size(); i++) {
      Object o = elements.getValue(i);
      if (o instanceof JsonObject jo) {
        if (want.equals(DesignBulkTypes.normalize(jo.getString("type", "")))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean resultsContainSuccessfulAcl(JsonArray results) {
    for (int i = 0; i < results.size(); i++) {
      Object o = results.getValue(i);
      if (!(o instanceof JsonObject jo)) {
        continue;
      }
      if (!"acl".equals(DesignBulkTypes.normalize(jo.getString("type", "")))) {
        continue;
      }
      return jo.getBoolean("success", false);
    }
    return false;
  }

  /**
   * @return true if the NSF was created
   */
  private static boolean openOrCreate(KeepJnxSession session, String nsfPath) throws Exception {
    DominoClient client = session.getClient();
    String server = "";
    String path = nsfPath;
    int bang = nsfPath.indexOf("!!");
    if (bang >= 0) {
      server = nsfPath.substring(0, bang);
      path = nsfPath.substring(bang + 2);
    }

    try {
      client.openDatabase(server, path);
      return false;
    } catch (Exception ignored) {
      // missing or not openable — try create
    }

    Database created =
        client.createDatabase(server, path, false, true, DominoClient.Encryption.None);
    DesignAcl.ensureDomainManagers(created);
    return true;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
  }
}
