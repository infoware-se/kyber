package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Page;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a page from raw DXL - PUT /pages?nsfPath=
 *
 * <p>Expects {@code dxl} in the incoming payload (set by the HTTP handler from the text/xml body).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreatePage extends AsyncDominoJNXJson {

  private static final String TYPE_PAGE = "page";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireRootName(dxl, TYPE_PAGE);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    signPage(db, name);

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE_PAGE)
            .put("name", name)
            .put("log", log));
  }

  private static void signPage(Database db, String name) throws KeepExceptionServer {
    Page page =
        db.getDesign()
            .getPage(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "Page %s missing after DXL import; cannot sign", name));
    page.sign();
    page.save();
  }
}
