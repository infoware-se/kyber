package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** PUT /named/{type}?nsfPath=&name= — typed update when path /{name} is unsafe. */
@MinimumAcl(AclLevel.DESIGNER)
public class UpdateNamed extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    NamedDesign.update(request);
  }
}
