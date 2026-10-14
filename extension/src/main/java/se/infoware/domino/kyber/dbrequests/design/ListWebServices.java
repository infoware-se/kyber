package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** List names — GET collection path (e.g. /agents)?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class ListWebServices extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    List<String> names = new ArrayList<>();
    for (WebServices.Entry e : WebServices.list(db, WebServices.Kind.PROVIDER)) {
      names.add(e.name());
    }
    request.emit(new JsonObject().put("WebServices", new JsonArray(names)));
  }
}
