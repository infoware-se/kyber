package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /fields?nsfPath=&type=form|subform&name= — field inventory without full DXL. */
@MinimumAcl(AclLevel.DESIGNER)
public class ListFields extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    request.emit(
        DesignStructure.listFields(
            request.db(), in.getString("type"), RequestParams.requireName(in)));
  }
}
