package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepException;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * PUT /database/category?nsfPath=&enabled=true|false — set or clear KyberEnabled. Does not use
 * {@link KyberRequest} so an unmarked NSF can be enabled.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class UpdateDatabaseCategory extends AsyncDominoJNXJson {

  @Override
  public void accessControlCheck(KeepJnxSession session, Database db) throws KeepException {
    super.accessControlCheck(session, db);
  }

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Boolean enabled = parseEnabled(request.incoming);
    if (enabled == null) {
      throw new KeepExceptionWrongDataRequest("enabled=true|false is required");
    }
    if (enabled) {
      DesignCatalog.mark(db);
    } else {
      DesignCatalog.unmark(db);
    }
    request.emit(
        ReplicaIds.put(
            new JsonObject()
                .put("success", true)
                .put("nsfPath", request.incoming.getString("nsfPath"))
                .put("enabled", DesignCatalog.has(db))
                .put("categories", db.getCategories() == null ? "" : db.getCategories()),
            db));
  }

  private static Boolean parseEnabled(JsonObject in) {
    if (in == null) {
      return null;
    }
    Object v = in.getValue("enabled");
    if (v instanceof Boolean b) {
      return b;
    }
    String s = in.getString("enabled");
    if (s == null || s.isBlank()) {
      return null;
    }
    if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
      return true;
    }
    if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
      return false;
    }
    return null;
  }
}
