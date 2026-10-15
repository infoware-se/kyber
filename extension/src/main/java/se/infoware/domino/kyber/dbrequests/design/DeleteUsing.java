package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.UsingDocument;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete the Using Document - DELETE /using?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteUsing extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);
    Optional<UsingDocument> opt = db.getDesign().getUsingDocument();
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("UsingDocument not found");
    }
    opt.get().delete();
    request.emit(new JsonObject().put("success", true).put("type", "helpusingdocument"));
  }
}
