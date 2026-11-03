package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** POST /rename?nsfPath=&type=&name=&newName= */
@MinimumAcl(AclLevel.DESIGNER)
public class RenameDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    String type = in.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    String name = RequestParams.requireName(in);
    String newName = in.getString("newName");
    if (newName == null || newName.isBlank()) {
      throw new KeepExceptionWrongDataRequest("newName is required");
    }
    request.emit(
        DesignCopyRename.rename(request.session(), request.db(), type, name, newName.trim()));
  }
}
