package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /templates?type=&kind= — starter names from kyber.nsf + JAR defaults. */
@MinimumAcl(AclLevel.READER)
public class ListTemplates extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String type = request.incoming.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    request.emit(
        DesignDefaults.listTemplates(
            request.session(), type, request.incoming.getString("kind")));
  }
}
