package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.Folder;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/** Delete one folder - DELETE /folders/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteFolder extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();

    Optional<Folder> folder = db.getDesign().getFolder(name);
    if (folder.isEmpty()) {
      throw new KeepExceptionWrongDataRequest("Folder not found: " + name);
    }

    folder.get().delete();
    request.emit(new JsonObject().put("success", true).put("name", name));
  }
}
