package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Update-only singleton (operationId createUsing kept for OpenAPI path remap to update). */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateUsing extends AsyncDominoJNXJson {
  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignCrud.updateSingleton(request, "using", "helpusingdocument");
  }
}
