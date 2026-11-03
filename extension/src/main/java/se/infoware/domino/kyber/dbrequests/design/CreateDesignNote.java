package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** POST /designnotes?nsfPath=&name= */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateDesignNote extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignCrud.createNamed(request, "designnote", "note", "fileresource");
  }
}
