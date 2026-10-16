package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Export one dataconnection as DXL - GET /dataconnections/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDataConnection extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    BulkOnlyNotes.Entry entry = ResidualDesign.require(db, "dataconnection", name);
    String dxl = ResidualDesign.exportDxl(db, entry.document());
    request.emit(DesignPayload.note(entry.name(), dxl));
  }
}