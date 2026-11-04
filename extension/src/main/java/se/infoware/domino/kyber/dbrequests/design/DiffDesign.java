package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * POST /diff — compare live note(s) and/or proposed DXL. Body may use nested {@code left}/{@code
 * right} ({@code type}+{@code name} or {@code dxl}) or flat {@code type}/{@code name} with {@code
 * dxl} or {@code toName}.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class DiffDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    request.emit(DesignDiff.diff(request.db(), request.incoming));
  }
}
