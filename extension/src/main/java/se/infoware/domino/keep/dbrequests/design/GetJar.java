package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Export one jar as DXL - GET /jars/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetJar extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    BulkOnlyNotes.Entry entry = ResidualDesign.require(db, "jar", name);
    String dxl = ResidualDesign.exportDxl(db, entry.document());
    request.emit(new JsonObject().put("name", entry.name()).put("dxl", dxl));
  }
}