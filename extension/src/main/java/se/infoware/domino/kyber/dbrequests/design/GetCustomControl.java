package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Export one Custom Control as DXL - GET /customcontrols/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetCustomControl extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    CustomControls.Entry entry = CustomControls.require(db, name);
    String dxl = CustomControls.exportDxl(db, entry.document());
    request.emit(DesignPayload.note(entry.name(), dxl));
  }
}
