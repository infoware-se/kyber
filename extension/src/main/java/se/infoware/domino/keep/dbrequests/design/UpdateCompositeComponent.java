package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Update-only — PUT /compositecomponents/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class UpdateCompositeComponent extends AsyncDominoJNXJson {
  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignCrud.updateNamed(request, "compositecomponent", "note", "fileresource");
  }
}
