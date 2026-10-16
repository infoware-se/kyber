package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Export one Web Service Provider as DXL - GET /webservices/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class GetWebService extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    WebServices.Entry entry = WebServices.require(db, WebServices.Kind.PROVIDER, name);
    String dxl = WebServices.exportDxl(db, entry.document());
    request.emit(DesignPayload.note(entry.name(), dxl));
  }
}
