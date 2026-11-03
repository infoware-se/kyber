package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** POST /defaults/preview?type=&name=&kind=&template= — resolve starter without write. */
@MinimumAcl(AclLevel.READER)
public class PreviewDefault extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String type = request.incoming.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    JsonObject preview =
        DesignDefaults.preview(
            request.session(),
            type,
            request.incoming.getString("kind"),
            request.incoming.getString("template"));
    String wanted = RequestParams.optionalName(request.incoming);
    if (wanted != null && preview.getString("dxl") != null) {
      preview.put("requestedName", wanted);
    }
    request.emit(preview);
  }
}
