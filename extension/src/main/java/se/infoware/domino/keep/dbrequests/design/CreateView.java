package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.View;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a view from raw DXL - PUT /views?nsfPath=
 *
 * <p>Expects {@code dxl} in the incoming payload (set by the HTTP handler from the text/xml body).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateView extends AsyncDominoJNXJson {

  private static final String TYPE_VIEW = "view";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireRootName(dxl, TYPE_VIEW);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    signView(db, name);

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE_VIEW)
            .put("name", name)
            .put("log", log));
  }

  private static void signView(Database db, String name) throws KeepExceptionServer {
    View view =
        db.getDesign()
            .getView(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "View %s missing after DXL import; cannot sign", name));
    view.sign();
    view.save();
  }
}
