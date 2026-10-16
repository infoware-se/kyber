package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.UsingDocument;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Export the Using Document as DXL - GET /using?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetUsing extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Optional<UsingDocument> opt = db.getDesign().getUsingDocument();
    if (opt.isEmpty()) {
      throw new KeepExceptionNotFound("UsingDocument not found");
    }
    UsingDocument el = opt.get();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    String dxl = exporter.exportDocument(el.getDocument());
    request.emit(DesignPayload.note(null, dxl).put("type", "helpusingdocument"));
  }
}
