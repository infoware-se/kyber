package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Frameset;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one frameset - DELETE /framesets/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteFrameset extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);

    Optional<Frameset> frameset;
    try {
      frameset = db.getDesign().getFrameset(name);
    } catch (RuntimeException e) {
      throw new KeepExceptionWrongDataRequest(
          "Frameset not readable (unsupported/missing version): " + name);
    }
    if (frameset.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Frameset not found: " + name);
    }

    frameset.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}
