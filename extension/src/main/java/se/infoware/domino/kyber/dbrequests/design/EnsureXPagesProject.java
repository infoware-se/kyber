package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Ensure minimal XPages project skeleton — POST /xpagesproject?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class EnsureXPagesProject extends AsyncDominoJNXJson {
  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    ImportDesignDxl.ensureEmptyDefaultsNsf(request.session(), DominoClient.Encryption.None);
    JsonObject result = XPagesProject.ensure(request.session(), request.db());
    request.emit(result.put("success", true));
  }
}
