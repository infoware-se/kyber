package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /columns?nsfPath=&type=view|folder&name= — column inventory without full DXL. */
@MinimumAcl(AclLevel.DESIGNER)
public class ListColumns extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    request.emit(
        DesignStructure.listColumns(
            request.db(), in.getString("type"), RequestParams.requireName(in)));
  }
}
