package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** List starter design DXL — GET /defaults */
@MinimumAcl(AclLevel.DESIGNER)
public class ListDefaultDesign extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    request.emit(
        new JsonObject()
            .put("defaultNsf", DesignDefaults.defaultNsfPath())
            .put("elements", DesignDefaults.resolveAll(request.session())));
  }
}
