package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Create-only singleton — POST /xspproperties */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateXspProperties extends KyberRequest {
  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignCrud.createSingleton(request, "xspproperties", "note", "fileresource");
  }
}
