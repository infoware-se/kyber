package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Bulk export of design notes as CRUD-identical DXL. GET /designdxl?nsfPath=
 *
 * <p>Requires Designer on the target NSF. Data documents and agent run data are not part of the
 * export.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class GetDesignDxl extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    String nsfPath = request.incoming.getString("nsfPath", "");
    JsonArray skipped = new JsonArray();
    request.emit(
        new JsonObject()
            .put("nsfPath", nsfPath)
            .put("elements", DesignElementIO.exportAll(db, skipped))
            .put("skipped", skipped));
  }
}
