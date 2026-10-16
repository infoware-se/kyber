package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Outline;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one outline - DELETE /outlines/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteOutline extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);

    Optional<Outline> outline = db.getDesign().getOutline(name);
    if (outline.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Outline not found: " + name);
    }

    outline.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}
