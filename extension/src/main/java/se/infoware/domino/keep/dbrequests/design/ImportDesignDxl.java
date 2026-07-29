package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.Acl;
import com.hcl.domino.security.AclEntry;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Bulk import design DXL - PUT /designdxl?nsfPath=
 *
 * <p>HTTP handler remaps query {@code nsfPath} (target) to {@code targetNsfPath} and opens
 * {@code KeepConfig.nsf} as the KEEP connection DB so a missing NSF can be created first.
 *
 * <p>This is the only endpoint that may create an NSF. Per-type CRUD requires an existing database.
 * The API never deletes databases.
 *
 * <p>On create, grants Manager to the JWT user plus LocalDomainAdmins / LocalDomainServers so the
 * new NSF is usable via REST and Notes.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class ImportDesignDxl extends AsyncDominoJNXJson {

  private static final String LOCAL_DOMAIN_ADMINS = "LocalDomainAdmins";
  private static final String LOCAL_DOMAIN_SERVERS = "LocalDomainServers";

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

    JsonObject payload = request.incoming.getJsonObject("body", request.incoming);
    JsonArray elements = payload.getJsonArray("elements");
    if (elements == null) {
      elements = request.incoming.getJsonArray("elements");
    }
    if (elements == null) {
      throw new KeepExceptionWrongDataRequest("elements array is required");
    }

    KeepJnxSession session = request.session();
    boolean created = openOrCreate(session, target);

    Database db = session.getDatabaseDomino(target);
    DxlImporter importer = session.getDxlImporter();

    List<JsonObject> ordered = DesignElementIO.sortForImport(elements);
    JsonArray results = new JsonArray();
    int ok = 0;
    int failed = 0;
    for (JsonObject el : ordered) {
      String type = el.getString("type", "");
      String dxl = el.getString("dxl", "");
      try {
        JsonObject one = DesignElementIO.importOne(importer, db, type, dxl);
        results.add(one);
        ok++;
      } catch (Exception e) {
        failed++;
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        JsonObject row =
            new JsonObject()
                .put("success", false)
                .put("type", type)
                .put("message", message);
        if (el.getString("name") != null) {
          row.put("name", el.getString("name"));
        }
        results.add(row);
      }
    }

    JsonObject compile = DesignLotusScriptCompile.compileAll(db);

    request.emit(
        new JsonObject()
            .put("success", failed == 0)
            .put("created", created)
            .put("nsfPath", target)
            .put("imported", ok)
            .put("failed", failed)
            .put("results", results)
            .put("lotusscript", compile));
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
    applyCreatorAcl(session, created);
    return true;
  }

  /**
   * Same idea as KEEP {@code CreateNSF} / domino-design domain ACL: caller + domain groups as
   * Manager so Notes and REST can open the new file.
   */
  private static void applyCreatorAcl(KeepJnxSession session, Database db) {
    Acl acl = db.getACL();

    String user = session.getUserName();
    if (user != null && !user.isBlank()) {
      ensureAclEntry(acl, user.trim(), AclLevel.MANAGER, EnumSet.of(AclFlag.PERSON));
    }

    ensureAclEntry(acl, LOCAL_DOMAIN_ADMINS, AclLevel.MANAGER, EnumSet.of(AclFlag.GROUP));
    ensureAclEntry(acl, LOCAL_DOMAIN_SERVERS, AclLevel.MANAGER, EnumSet.of(AclFlag.SERVER));

    String adminServer = db.getServer();
    if (adminServer != null && !adminServer.isBlank()) {
      acl.setAdminServer(adminServer.trim());
      ensureAclEntry(
          acl, adminServer.trim(), AclLevel.MANAGER, EnumSet.of(AclFlag.SERVER, AclFlag.ADMIN_SERVER));
    }

    acl.save();
  }

  private static void ensureAclEntry(
      Acl acl, String name, AclLevel level, Collection<AclFlag> flags) {
    Optional<AclEntry> existing = acl.getEntry(name);
    if (existing.isPresent()) {
      acl.updateEntry(name, name, level, List.of(), flags);
    } else {
      acl.addEntry(name, level, List.of(), flags);
    }
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
