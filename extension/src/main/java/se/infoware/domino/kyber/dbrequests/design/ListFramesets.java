package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.stream.Stream;

/** List names — GET collection path (e.g. /agents)?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class ListFramesets extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    try {
      request.emit(DesignListing.elements("Framesets", db.getDesign().getFramesets()));
    } catch (RuntimeException ex) {
      request.emit(DesignListing.elements("Framesets", Stream.<com.hcl.domino.design.Frameset>empty()));
    }
  }
}
