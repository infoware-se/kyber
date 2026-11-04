package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.logging.Logger;
import se.infoware.domino.kyber.KyberLog;

/** POST /compile?nsfPath= — Recompile All LotusScript equivalent. */
@MinimumAcl(AclLevel.DESIGNER)
public class CompileDesign extends KyberRequest {

  private static final Logger LOG = KyberLog.of(CompileDesign.class);

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject result = DesignLotusScriptCompile.compileAll(request.db());
    JsonArray errors = result.getJsonArray("errors");
    boolean ok = errors == null || errors.isEmpty();
    result.put("success", ok);
    if (ok) {
      KyberLog.info(LOG, "compile ok nsf=" + KyberLog.nsf(request.db()));
    } else {
      KyberLog.warn(
          LOG,
          "compile failed nsf=" + KyberLog.nsf(request.db()) + " errors=" + errors.size());
    }
    request.emit(result);
  }
}
