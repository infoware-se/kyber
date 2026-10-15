package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.XPage;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one XPage as DXL - GET /xpages/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetXPage extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    Optional<XPage> opt = db.getDesign().getXPage(name);
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("XPage not found: " + name);
    }
    XPage el = opt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(el.getDocument());
    request.emit(DesignPayload.note(name, dxl));
  }
}
