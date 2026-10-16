package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** List names — GET collection path (e.g. /agents)?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class ListSharedActions extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    List<JsonObject> rows = new ArrayList<>();
    for (var el : SharedActionsNotes.list(db)) {
      String name = SharedActionsNotes.nameOf(el);
      if (name != null && !name.isBlank()) {
        rows.add(DesignListing.row(name, el, null));
      }
    }
    request.emit(DesignListing.named("SharedActions", rows));
  }
}
