package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * GET /schedules?nsfPath= (&trigger=&scheduledOnly=) — NSF-wide agent trigger/schedule inventory
 * without full DXL.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDesignSchedules extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    request.emit(DesignSchedules.inventory(request.db(), request.incoming));
  }
}
