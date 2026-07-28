package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DbProperties;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace Application Properties from DXL - PUT /applicationproperties?nsfPath=
 *
 * <p>Accepts exported icon-note DXL ({@code <note>}) or a {@code <database>} wrapper (uses
 * replaceDbProperties). There is intentionally no DELETE: Domino creates the icon note with
 * every NSF and it must stay.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateApplicationProperties extends AsyncDominoJNXJson {

  private static final String TYPE = "applicationproperties";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String root = DesignDxl.requireRootOnlyReturningLocalName(dxl, "note", "database");

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log;
    if ("database".equalsIgnoreCase(root)) {
      log = DxlDesignPatch.importWithDbProperties(importer, dxl, db);
    } else {
      log = DxlDesignPatch.importDesign(importer, dxl, db);
    }

    DbProperties props = db.getDesign().getDatabaseProperties();
    if (props == null || props.getDocument() == null) {
      throw new KeepExceptionServer(
          "Application Properties missing after DXL import; cannot sign");
    }
    props.sign();
    props.save();

    request.emit(
        new JsonObject().put("success", true).put("type", TYPE).put("log", log));
  }
}
