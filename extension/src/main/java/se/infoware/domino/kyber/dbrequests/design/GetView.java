package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.View;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one view as DXL - GET /views/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetView extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();

    Optional<View> viewOpt = db.getDesign().getView(name);
    if (viewOpt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("View not found: " + name);
    }

    View view = viewOpt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(view.getDocument());

    request.emit(DesignPayload.note(name, dxl));
  }
}
