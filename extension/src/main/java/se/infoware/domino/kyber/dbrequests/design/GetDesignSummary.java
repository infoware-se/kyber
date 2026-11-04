package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * GET /summary?nsfPath=&type=&name= or unid=|noteId= — inventory metadata without full DXL by
 * default ({@code includeDxl=true} to attach DXL).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDesignSummary extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    request.emit(DesignSummary.summarize(request.db(), request.incoming));
  }
}
