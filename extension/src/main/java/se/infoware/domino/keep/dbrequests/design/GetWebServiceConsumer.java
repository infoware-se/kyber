package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Export one Web Service Consumer as DXL - GET /webserviceconsumers/{name}?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetWebServiceConsumer extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    WebServices.Entry entry = WebServices.require(db, WebServices.Kind.CONSUMER, name);
    String dxl = WebServices.exportDxl(db, entry.document());
    request.emit(new JsonObject().put("name", entry.name()).put("dxl", dxl));
  }
}
