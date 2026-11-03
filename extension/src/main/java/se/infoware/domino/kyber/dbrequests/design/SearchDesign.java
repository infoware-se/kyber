package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /search?nsfPath=&q=&types=&match=&limit= */
@MinimumAcl(AclLevel.DESIGNER)
public class SearchDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    Integer limit = null;
    if (in.getValue("limit") instanceof Number n) {
      limit = n.intValue();
    } else if (in.getString("limit") != null && !in.getString("limit").isBlank()) {
      try {
        limit = Integer.parseInt(in.getString("limit").trim());
      } catch (NumberFormatException e) {
        limit = null;
      }
    }
    request.emit(
        DesignResolve.search(
            request.db(),
            in.getString("q"),
            in.getString("types"),
            in.getString("match"),
            limit));
  }
}
