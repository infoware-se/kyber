package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * POST /database/inherit?nsfPath=&templateServer= — Domino design refresh from the linked template
 * ({@link Database#refreshDesign(String)}).
 *
 * <p>Domino / KEEP failures propagate with their own status. They are not coerced to HTTP 400.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class InheritDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    String server = request.incoming.getString("templateServer");
    if (server == null || server.isBlank()) {
      // Empty string = local/current server in Domino refreshDesign.
      server = "";
    } else {
      server = server.trim();
    }
    String before = db.getDesignTemplateName();
    db.refreshDesign(server);
    request.emit(
        ReplicaIds.put(
            new JsonObject()
                .put("success", true)
                .put("nsfPath", request.incoming.getString("nsfPath"))
                .put("templateServer", server)
                .put("designTemplateName", db.getDesignTemplateName())
                .put("designTemplateNameBefore", before == null ? "" : before),
            db));
  }
}
