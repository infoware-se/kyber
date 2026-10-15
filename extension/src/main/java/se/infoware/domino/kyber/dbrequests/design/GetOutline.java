package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Outline;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one outline as DXL - GET /outlines/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetOutline extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();

    Optional<Outline> outlineOpt = db.getDesign().getOutline(name);
    if (outlineOpt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Outline not found: " + name);
    }

    Outline outline = outlineOpt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(outline.getDocument());

    request.emit(DesignPayload.note(name, dxl));
  }
}
