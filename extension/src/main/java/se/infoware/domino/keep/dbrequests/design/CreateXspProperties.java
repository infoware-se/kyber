package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Create/replace Xsp Properties from raw DXL - PUT /xspproperties?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateXspProperties extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    Database db = request.db();
    KeepJnxSession session = request.session();
    DxlImporter importer = session.getDxlImporter();
    JsonObject result = DesignElementIO.importOne(importer, db, "xspproperties", dxl, session);
    request.emit(result);
  }
}
