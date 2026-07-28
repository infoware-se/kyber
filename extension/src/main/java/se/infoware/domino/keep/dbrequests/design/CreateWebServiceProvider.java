package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a Web Service Provider from raw DXL - PUT /webserviceproviders?nsfPath=
 *
 * <p>Designer exports are {@code <note>} with {@code $Flags} containing {@code {} }.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateWebServiceProvider extends AsyncDominoJNXJson {

  private static final String TYPE = "webservice";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireDesignName(dxl, "note", "fileresource", "webservice", "agent");

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    WebServices.Entry entry =
        WebServices.find(db, WebServices.Kind.PROVIDER, name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "WebServiceProvider %s missing after DXL import; cannot sign", name));
    DesignElement el =
        WebServices.asDesignElement(db, entry.document())
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "WebServiceProvider %s not loadable as DesignElement after import", name));
    el.sign();
    el.save();

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE)
            .put("name", entry.name())
            .put("log", log));
  }
}
