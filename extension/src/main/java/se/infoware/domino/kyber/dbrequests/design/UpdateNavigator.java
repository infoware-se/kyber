package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Update-only — PUT /navigators/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class UpdateNavigator extends KyberRequest {
  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignCrud.updateNamed(request, "navigator", "navigator", "note");
  }
}
