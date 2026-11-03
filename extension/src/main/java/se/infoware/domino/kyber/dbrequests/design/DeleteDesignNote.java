package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** DELETE /designnotes/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteDesignNote extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    DesignElementLookup.deleteNamed(request.db(), "designnote", name);
    request.emit(new JsonObject().put("success", true).put("name", name).put("type", "designnote"));
  }
}
