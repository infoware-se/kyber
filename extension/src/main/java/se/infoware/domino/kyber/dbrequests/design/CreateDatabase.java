package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.ErrorClassification;
import com.hcl.domino.keep.exceptions.KeepException;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create one NSF. POST /database?nsfPath=&amp;dbTitle=
 *
 * <p>The HTTP handler opens {@code KeepConfig.nsf} so this call can run when the target file is
 * missing. {@link MinimumAcl} applies to that bootstrap database. Creating the file checks the
 * server privilege Create databases &amp; templates. An existing file is left unchanged and the
 * call responds 409.
 */
@MinimumAcl(AclLevel.READER)
public class CreateDatabase extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String target =
        firstNonBlank(
            request.incoming.getString("targetNsfPath"), request.incoming.getString("nsfPath"));
    if (target == null || target.isBlank()) {
      throw new KeepExceptionWrongDataRequest("nsfPath (target) is required");
    }
    target = target.trim();
    if (ImportDesign.isKeepConfig(target)) {
      throw new KeepExceptionWrongDataRequest("Cannot create KeepConfig.nsf");
    }

    KeepJnxSession session = request.session();
    DominoClient.Encryption encryption = ImportDesign.encryptionOf(request.incoming.getString("encryption"));
    if (ImportDesign.databaseExists(session, target)) {
      throw KeepException.status("Database already exists: " + target, ErrorClassification.CONFLICT);
    }

    String title = blankToNull(request.incoming.getString("dbTitle"));
    if (ImportDesign.isDefaultNsfPath(target)) {
      ImportDesign.ensureEmptyDefaultsNsf(session, encryption);
      if (title != null) {
        session.getDatabaseDomino(target).setTitle(title);
      }
    } else {
      ImportDesign.ensureEmptyDefaultsNsf(session, encryption);
      if (!ImportDesign.openOrCreate(session, target, encryption)) {
        throw KeepException.status(
            "Database already exists: " + target, ErrorClassification.CONFLICT);
      }
      Database db = session.getDatabaseDomino(target);
      if (title == null) {
        title = ImportDesign.defaultTitleForNewNsf(target);
      }
      if (title != null) {
        db.setTitle(title);
      }
      DxlImporter importer = session.getDxlImporter();
      ImportDesign.applyProductDbIcon(importer, db, session);
    }

    JsonObject out =
        ReplicaIds.put(
            new JsonObject().put("success", true).put("created", true).put("nsfPath", target),
            session.getDatabaseDomino(target));
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
