package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.AboutDocument;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete the About Document - DELETE /about?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteAbout extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Optional<AboutDocument> opt = db.getDesign().getAboutDocument();
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("AboutDocument not found");
    }
    opt.get().delete();
    request.emit(new JsonObject().put("success", true).put("type", "helpaboutdocument"));
  }
}
