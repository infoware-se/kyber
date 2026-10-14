package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** One starter design DXL — GET /defaults/{type}?kind= */
@MinimumAcl(AclLevel.READER)
public class GetDefault extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String type = request.incoming.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type path parameter is required");
    }
    type = URLDecoder.decode(type, StandardCharsets.UTF_8);
    String kind = request.incoming.getString("kind");
    JsonObject one = DesignDefaults.resolveOne(request.session(), type, kind);
    request.emit(one.put("defaultNsf", DesignDefaults.defaultNsfPath()));
  }
}
