package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.SharedActions;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete Shared Actions - DELETE /sharedactions?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteSharedActions extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Optional<SharedActions> opt = db.getDesign().getSharedActions();
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("SharedActions not found");
    }
    opt.get().delete();
    request.emit(new JsonObject().put("success", true).put("type", "sharedactions"));
  }
}
