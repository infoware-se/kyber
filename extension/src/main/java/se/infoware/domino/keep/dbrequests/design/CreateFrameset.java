package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Frameset;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a frameset from raw DXL - PUT /framesets?nsfPath=
 *
 * <p>Expects {@code dxl} in the incoming payload (set by the HTTP handler from the text/xml body).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateFrameset extends AsyncDominoJNXJson {

  private static final String TYPE_FRAMESET = "frameset";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireRootName(dxl, TYPE_FRAMESET);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    signFrameset(db, name);

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE_FRAMESET)
            .put("name", name)
            .put("log", log));
  }

  private static void signFrameset(Database db, String name) throws KeepExceptionServer {
    Frameset frameset;
    try {
      frameset =
          db.getDesign()
              .getFrameset(name)
              .orElseThrow(
                  () ->
                      new KeepExceptionServer(
                          "Frameset %s missing after DXL import; cannot sign", name));
    } catch (KeepExceptionServer e) {
      throw e;
    } catch (RuntimeException e) {
      // JNX may fail wrapping framesets with unsupported/missing version
      throw new KeepExceptionServer(
          "Frameset %s imported but cannot be signed (%s)",
          name, e.getMessage() == null ? e.toString() : e.getMessage());
    }
    frameset.sign();
    frameset.save();
  }
}
