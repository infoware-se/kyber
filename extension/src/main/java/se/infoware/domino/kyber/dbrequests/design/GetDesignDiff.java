package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /diff?nsfPath=&type=&name=&toName= (&toType=) — compare two notes in the NSF. */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDesignDiff extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    String toName = in.getString("toName");
    if (toName == null || toName.isBlank()) {
      toName = in.getString("toname");
    }
    request.emit(
        DesignDiff.diffNamed(
            request.db(),
            in.getString("type"),
            RequestParams.requireName(in),
            in.getString("toType"),
            toName));
  }
}
