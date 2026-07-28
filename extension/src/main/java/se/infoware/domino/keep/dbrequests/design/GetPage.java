package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Page;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one page as DXL - GET /pages/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetPage extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();

    Optional<Page> pageOpt = db.getDesign().getPage(name);
    if (pageOpt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Page not found: " + name);
    }

    Page page = pageOpt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(page.getDocument());

    request.emit(new JsonObject().put("name", name).put("dxl", dxl));
  }
}
