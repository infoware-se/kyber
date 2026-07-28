package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** List shared field names - GET /sharedfieldnames?nsfPath= */
@MinimumAcl(AclLevel.READER)
public class GetSharedFieldNames extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    List<String> names = new ArrayList<>();
    db.getDesign()
        .getSharedFields()
        .forEach(
            el -> {
              String title = el.getTitle();
              if (title != null && !title.isBlank()) {
                names.add(title);
              }
            });
    Collections.sort(names);
    request.emit(new JsonObject().put("Fields", new JsonArray(names)));
  }
}
