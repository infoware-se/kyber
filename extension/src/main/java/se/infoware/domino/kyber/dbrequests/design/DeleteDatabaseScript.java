package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DatabaseScriptLibrary;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete Database Script - DELETE /databasescript?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteDatabaseScript extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    Optional<DatabaseScriptLibrary> opt = db.getDesign().getDatabaseScriptLibrary();
    if (opt.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("DatabaseScript not found");
    }
    opt.get().delete();
    request.emit(new JsonObject().put("success", true).put("type", "databasescript"));
  }
}
