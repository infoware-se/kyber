package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** GET /designnotes/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDesignNote extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    BulkOnlyNotes.Entry entry = ResidualDesign.require(request.db(), "designnote", name);
    String dxl = ResidualDesign.exportDxl(request.db(), entry.document());
    request.emit(DesignPayload.note(entry.name(), dxl));
  }
}
