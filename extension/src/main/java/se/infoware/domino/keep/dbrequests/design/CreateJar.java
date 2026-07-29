package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Create/replace a jar from raw DXL - PUT /= */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateJar extends AsyncDominoJNXJson {

  private static final String TYPE = "jar";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String[] result = ResidualDesign.importAndSign(db, importer, TYPE, dxl, "note","fileresource");
    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE)
            .put("name", result[0])
            .put("log", result[1]));
  }
}