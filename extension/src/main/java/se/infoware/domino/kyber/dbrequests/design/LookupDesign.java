package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /lookup?nsfPath=&unid=|noteId=&includeDxl= */
@MinimumAcl(AclLevel.DESIGNER)
public class LookupDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    boolean includeDxl = !"false".equalsIgnoreCase(String.valueOf(in.getValue("includeDxl")));
    if (in.containsKey("includeDxl") && in.getValue("includeDxl") instanceof Boolean b) {
      includeDxl = b;
    }
    request.emit(
        DesignResolve.lookup(
            request.db(), in.getString("unid"), in.getString("noteId"), includeDxl));
  }
}
