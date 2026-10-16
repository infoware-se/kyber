package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.SharedColumn;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one shared column as DXL - GET /sharedcolumns/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetSharedColumn extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    Optional<SharedColumn> opt = db.getDesign().getSharedColumn(name);
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("SharedColumn not found: " + name);
    }
    SharedColumn el = opt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(el.getDocument());
    request.emit(DesignPayload.note(name, dxl));
  }
}
