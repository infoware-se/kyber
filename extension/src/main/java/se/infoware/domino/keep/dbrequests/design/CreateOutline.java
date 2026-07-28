package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Outline;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace an outline from raw DXL - PUT /outlines?nsfPath=
 *
 * <p>Expects {@code dxl} in the incoming payload (set by the HTTP handler from the text/xml body).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateOutline extends AsyncDominoJNXJson {

  private static final String TYPE_OUTLINE = "outline";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireRootName(dxl, TYPE_OUTLINE);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    signOutline(db, name);

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE_OUTLINE)
            .put("name", name)
            .put("log", log));
  }

  private static void signOutline(Database db, String name) throws KeepExceptionServer {
    Outline outline =
        db.getDesign()
            .getOutline(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "Outline %s missing after DXL import; cannot sign", name));
    outline.sign();
    outline.save();
  }
}
