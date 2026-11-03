package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** POST /compile?nsfPath= — Recompile All LotusScript equivalent. */
@MinimumAcl(AclLevel.DESIGNER)
public class CompileDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject result = DesignLotusScriptCompile.compileAll(request.db());
    result.put("success", result.getJsonArray("errors") == null
        || result.getJsonArray("errors").isEmpty());
    request.emit(result);
  }
}
