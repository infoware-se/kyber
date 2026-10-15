package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.StyleSheet;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one style sheet - DELETE /stylesheets/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteStyleSheet extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);
    Optional<StyleSheet> opt = db.getDesign().getStyleSheet(name);
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("StyleSheet not found: " + name);
    }
    opt.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}
