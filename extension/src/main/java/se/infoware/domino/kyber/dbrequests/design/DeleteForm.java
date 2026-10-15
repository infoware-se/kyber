package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Form;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one form - DELETE /forms/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteForm extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);

    Optional<Form> form = db.getDesign().getForm(name);
    if (form.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Form not found: " + name);
    }

    form.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}
