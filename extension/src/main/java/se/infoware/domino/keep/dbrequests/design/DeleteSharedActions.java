package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Delete one Shared Actions note - DELETE /sharedactions/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteSharedActions extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    SharedActionsNotes.require(db, name).delete();
    request.emit(
        new JsonObject().put("success", true).put("type", "sharedactions").put("name", name));
  }
}
