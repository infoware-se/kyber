package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DbProperties;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Export Application Properties (icon note / DbProperties) as DXL - GET
 * /applicationproperties?nsfPath=
 */
@MinimumAcl(AclLevel.READER)
public class GetApplicationProperties extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    DbProperties props = db.getDesign().getDatabaseProperties();
    if (props == null || props.getDocument() == null) {
      throw new KeepExceptionWrongDataRequest("Application Properties not found");
    }
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(props.getDocument());
    request.emit(new JsonObject().put("type", "applicationproperties").put("dxl", dxl));
  }
}
