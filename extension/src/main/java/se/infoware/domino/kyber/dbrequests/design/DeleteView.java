package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.View;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one view - DELETE /views/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteView extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);

    Optional<View> view = db.getDesign().getView(name);
    if (view.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("View not found: " + name);
    }

    view.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}
