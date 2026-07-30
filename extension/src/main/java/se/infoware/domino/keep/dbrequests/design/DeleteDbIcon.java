package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Delete database icon ($DBIcon) — DELETE /dbicon?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteDbIcon extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    var opt = request.db().getDesign().getImageResource(DesignElementLookup.DBICON_NAME);
    if (opt.isEmpty()) {
      throw new KeepExceptionNotFound("DbIcon not found");
    }
    opt.get().delete();
    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", "dbicon")
            .put("name", DesignElementLookup.DBICON_NAME));
  }
}
