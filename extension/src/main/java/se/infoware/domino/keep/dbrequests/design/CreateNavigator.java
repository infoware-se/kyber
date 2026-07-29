package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Navigator;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Create/replace a navigator from raw DXL - PUT /navigators?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateNavigator extends AsyncDominoJNXJson {

  private static final String TYPE = "navigator";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireDesignName(dxl, "navigator","note");

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    Navigator el =
        db.getDesign().getNavigator(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "Navigator %s missing after DXL import; cannot sign", name));
    el.sign();
    el.save();

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE)
            .put("name", name)
            .put("log", log));
  }
}