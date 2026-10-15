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
public class ListWebServices extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    List<JsonObject> rows = new ArrayList<>();
    for (WebServices.Entry entry : WebServices.list(db, WebServices.Kind.PROVIDER)) {
      if (entry.name() != null && !entry.name().isBlank()) {
        rows.add(DesignListing.row(entry.name(), entry.document()));
      }
    }
    request.emit(DesignListing.named("WebServices", rows));
  }
}
