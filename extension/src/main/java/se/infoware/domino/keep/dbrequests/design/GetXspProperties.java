package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export Xsp Properties (WEB-INF/xsp.properties) as DXL - GET /xspproperties?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetXspProperties extends AsyncDominoJNXJson {

  static final String NAME = "WEB-INF/xsp.properties";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Optional<BulkOnlyNotes.Entry> entry = BulkOnlyNotes.find(db, "xspproperties", NAME);
    if (entry.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Xsp Properties not found: " + NAME);
    }
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(entry.get().document());
    request.emit(
        new JsonObject()
            .put("type", "xspproperties")
            .put("name", entry.get().name())
            .put("dxl", dxl));
  }
}
