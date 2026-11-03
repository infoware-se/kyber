package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** POST /copy?nsfPath=&type=&name=&toName=&toNsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class CopyDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    String type = in.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    String name = RequestParams.requireName(in);
    String toName = in.getString("toName");
    if (toName == null || toName.isBlank()) {
      throw new KeepExceptionWrongDataRequest("toName is required");
    }
    KeepJnxSession session = request.session();
    Database source = request.db();
    String toNsf = in.getString("toNsfPath");
    Database target = source;
    if (toNsf != null && !toNsf.isBlank() && !toNsf.trim().equalsIgnoreCase(in.getString("nsfPath"))) {
      target = session.getDatabaseDomino(toNsf.trim());
      DesignCatalog.require(target);
    }
    request.emit(
        DesignCopyRename.copy(session, source, type, name, target, toName.trim()));
  }
}
