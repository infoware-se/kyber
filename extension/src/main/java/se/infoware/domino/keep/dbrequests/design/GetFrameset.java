package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Frameset;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one frameset as DXL - GET /framesets/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetFrameset extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();

    Optional<Frameset> framesetOpt;
    try {
      framesetOpt = db.getDesign().getFrameset(name);
    } catch (RuntimeException e) {
      throw new KeepExceptionWrongDataRequest(
          "Frameset not readable (unsupported/missing version): " + name);
    }
    if (framesetOpt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Frameset not found: " + name);
    }

    Frameset frameset = framesetOpt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(frameset.getDocument());

    request.emit(new JsonObject().put("name", name).put("dxl", dxl));
  }
}
