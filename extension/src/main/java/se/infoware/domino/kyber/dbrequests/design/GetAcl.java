package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.io.StringWriter;

/** Export database ACL as DXL — GET /acl?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetAcl extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    StringWriter sw = new StringWriter();
    exporter.exportACL(db, sw);
    request.emit(new JsonObject().put("type", "acl").put("dxl", sw.toString()));
  }
}
