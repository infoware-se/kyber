package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Replace the database ACL from raw DXL — PUT /acl?nsfPath= */
@MinimumAcl(AclLevel.MANAGER)
public class UpdateAcl extends KyberRequest {

  private static final String TYPE = "acl";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    DesignDxl.requireRootOnly(dxl, TYPE);
    ImportDesign.ensureEmptyDefaultsNsf(request.session(), DominoClient.Encryption.None);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importAcl(importer, dxl, db);

    request.emit(
        new JsonObject().put("success", true).put("type", TYPE).put("log", log));
  }
}
