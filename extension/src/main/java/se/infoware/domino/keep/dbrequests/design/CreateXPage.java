package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.XPage;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace an XPage from raw DXL - PUT /xpages?nsfPath=
 *
 * <p>Domino 14.5 has no first-class {@code <xpage>} root; exports are typically {@code <note>} or
 * {@code <fileresource>}. Custom Controls are not supported (different design flags).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateXPage extends AsyncDominoJNXJson {

  private static final String TYPE = "xpage";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireDesignName(dxl, "note", "fileresource", "xpage");

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    XPage el =
        db.getDesign()
            .getXPage(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "XPage %s missing after DXL import; cannot sign", name));
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
