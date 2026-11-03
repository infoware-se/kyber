package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /content?nsfPath=&type=&name= — binary as contentBase64 JSON. */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDesignContent extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    String type = in.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    String name =
        "dbicon".equalsIgnoreCase(DesignBulkTypes.normalize(type))
            ? DesignElementLookup.DBICON_NAME
            : RequestParams.requireName(in);
    request.emit(BinaryDesignResource.get(request.db(), type, name));
  }
}
