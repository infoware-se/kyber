package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one compositeapplication - DELETE /compositeapplications/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteCompositeApplication extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    Optional<? extends DesignElement> opt = db.getDesign().getCompositeApplication(name);
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("CompositeApplication not found: " + name);
    }
    opt.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}