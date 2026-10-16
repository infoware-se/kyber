package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Delete one NSF. DELETE /database?nsfPath=
 *
 * <p>Needs Manager on that NSF. {@link KyberRequest} also requires the catalog category
 * KyberEnabled before this method runs. The file is removed from the server. {@code
 * KeepConfig.nsf} is refused.
 */
@MinimumAcl(AclLevel.MANAGER)
public class DeleteDatabase extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String target = request.incoming.getString("nsfPath", "");
    if (target == null || target.isBlank()) {
      throw new KeepExceptionWrongDataRequest("nsfPath is required");
    }
    target = target.trim();
    if (ImportDesign.isKeepConfig(target)) {
      throw new KeepExceptionWrongDataRequest("Cannot delete KeepConfig.nsf");
    }

    Database open = request.db();
    JsonObject out =
        ReplicaIds.put(new JsonObject().put("success", true).put("nsfPath", target), open);
    if (open != null) {
      open.close();
    }
    String[] where = ImportDesign.serverAndPath(target);
    request.session().getClient().deleteDatabase(where[0], where[1]);
    request.emit(out);
  }
}
