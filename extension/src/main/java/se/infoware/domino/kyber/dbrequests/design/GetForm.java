package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Form;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export one form as DXL - GET /forms/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetForm extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();

    Optional<Form> formOpt = db.getDesign().getForm(name);
    if (formOpt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Form not found: " + name);
    }

    Form form = formOpt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(form.getDocument());

    request.emit(DesignPayload.note(name, dxl));
  }
}
