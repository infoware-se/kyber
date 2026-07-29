package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one compositecomponent as DXL - GET /compositecomponents/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetCompositeComponent extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    Optional<? extends DesignElement> opt = db.getDesign().getCompositeComponent(name);
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("CompositeComponent not found: " + name);
    }
    DesignElement el = opt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(el.getDocument());
    request.emit(new JsonObject().put("name", name).put("dxl", dxl));
  }
}