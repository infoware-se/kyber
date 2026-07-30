package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Upsert (deprecated) — PUT /framesets?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateFrameset extends AsyncDominoJNXJson {
  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DesignCrud.upsertNamed(request, "frameset", "frameset");
  }
}
