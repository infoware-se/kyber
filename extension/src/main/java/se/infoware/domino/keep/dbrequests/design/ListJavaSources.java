package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** List names — GET collection path (e.g. /agents)?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class ListJavaSources extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    request.emit(
        new JsonObject().put("JavaSources", new JsonArray(ResidualDesign.listNames(db, "javasource"))));
  }
}