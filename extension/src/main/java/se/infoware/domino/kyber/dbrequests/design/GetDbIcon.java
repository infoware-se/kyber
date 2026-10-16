package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Export database icon ($DBIcon) — GET /dbicon?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDbIcon extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl =
        DesignElementLookup.exportDxl(request.db(), "dbicon", DesignElementLookup.DBICON_NAME)
            .orElseThrow(() -> new KeepExceptionNotFound("DbIcon not found"));
    request.emit(
        DesignPayload.note(DesignElementLookup.DBICON_NAME, dxl).put("type", "dbicon"));
  }
}
