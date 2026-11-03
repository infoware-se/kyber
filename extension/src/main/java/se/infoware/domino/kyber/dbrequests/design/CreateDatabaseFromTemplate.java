package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.ErrorClassification;
import com.hcl.domino.keep.exceptions.KeepException;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * POST /database/fromTemplate?nsfPath=&templateNsfPath=&dbTitle= — create NSF from an NTF/NSF
 * template via JNX {@code createDatabaseFromTemplate}, then mark KyberEnabled.
 */
@MinimumAcl(AclLevel.READER)
public class CreateDatabaseFromTemplate extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String target =
        firstNonBlank(
            request.incoming.getString("targetNsfPath"), request.incoming.getString("nsfPath"));
    String template = request.incoming.getString("templateNsfPath");
    if (target == null || target.isBlank()) {
      throw new KeepExceptionWrongDataRequest("nsfPath (target) is required");
    }
    if (template == null || template.isBlank()) {
      throw new KeepExceptionWrongDataRequest("templateNsfPath is required");
    }
    target = target.trim();
    template = template.trim();
    if (ImportDesign.isKeepConfig(target)) {
      throw new KeepExceptionWrongDataRequest("Cannot create KeepConfig.nsf");
    }

    KeepJnxSession session = request.session();
    DominoClient.Encryption encryption =
        ImportDesign.encryptionOf(request.incoming.getString("encryption"));
    if (ImportDesign.databaseExists(session, target)) {
      throw KeepException.status("Database already exists: " + target, ErrorClassification.CONFLICT);
    }

    DominoClient client = session.getClient();
    String[] dest = ImportDesign.serverAndPath(target);
    String[] src = ImportDesign.serverAndPath(template);
    Database created =
        client.createDatabaseFromTemplate(src[0], src[1], dest[0], dest[1], encryption);
    if (created == null) {
      throw new KeepExceptionWrongDataRequest("createDatabaseFromTemplate returned null");
    }
    Database db = session.getDatabaseDomino(target);
    String title = blankToNull(request.incoming.getString("dbTitle"));
    if (title != null) {
      db.setTitle(title);
    }
    DesignAcl.ensureApplicationAcl(
        db, client.getEffectiveUserName(), dest[0] == null || dest[0].isBlank() ? db.getServer() : dest[0]);
    DesignCatalog.mark(db);
    JsonObject out =
        ReplicaIds.put(
            new JsonObject()
                .put("success", true)
                .put("created", true)
                .put("nsfPath", target)
                .put("templateNsfPath", template),
            db);
    if (title != null) {
      out.put("dbTitle", title);
    }
    request.emit(out);
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

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
