package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.SharedActions;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export Shared Actions as DXL - GET /sharedactions?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetSharedActions extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Optional<SharedActions> opt = db.getDesign().getSharedActions();
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("SharedActions not found");
    }
    SharedActions el = opt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(el.getDocument());
    request.emit(new JsonObject().put("type", "sharedactions").put("dxl", dxl));
  }
}
