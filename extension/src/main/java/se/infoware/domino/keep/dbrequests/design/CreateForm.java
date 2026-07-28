package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Form;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a form from raw DXL - PUT /forms?nsfPath=
 *
 * <p>Expects {@code dxl} in the incoming payload (set by the HTTP handler from the text/xml body).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateForm extends AsyncDominoJNXJson {

  private static final String TYPE_FORM = "form";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireRootName(dxl, TYPE_FORM);

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    signForm(db, name);

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE_FORM)
            .put("name", name)
            .put("log", log));
  }

  private static void signForm(Database db, String name) throws KeepExceptionServer {
    Form form =
        db.getDesign()
            .getForm(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "Form %s missing after DXL import; cannot sign", name));
    form.sign();
    form.save();
  }
}
