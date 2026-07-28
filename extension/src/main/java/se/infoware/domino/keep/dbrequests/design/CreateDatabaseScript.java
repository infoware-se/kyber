package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DatabaseScriptLibrary;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Create/replace Database Script from raw DXL - PUT /databasescript?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateDatabaseScript extends AsyncDominoJNXJson {

  private static final String TYPE = "databasescript";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    DesignDxl.requireRootOnly(dxl, TYPE);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    DatabaseScriptLibrary el =
        db.getDesign()
            .getDatabaseScriptLibrary()
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "DatabaseScript missing after DXL import; cannot sign"));
    el.sign();
    el.save();

    request.emit(
        new JsonObject().put("success", true).put("type", TYPE).put("log", log));
  }
}
