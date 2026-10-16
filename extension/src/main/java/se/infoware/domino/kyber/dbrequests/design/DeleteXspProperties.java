package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Delete XPages xsp.properties — DELETE /xspproperties?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteXspProperties extends KyberRequest {

  private static final String NAME = "WEB-INF/xsp.properties";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignAccess.requireDeleteDocuments(request.session(), request.db());
    var entry = BulkOnlyNotes.find(request.db(), "xspproperties", NAME);
    if (entry.isEmpty()) {
      throw new KeepExceptionNotFound("Xsp Properties not found: " + NAME);
    }
    entry.get().document().delete();
    request.emit(
        new JsonObject().put("success", true).put("type", "xspproperties").put("name", NAME));
  }
}
