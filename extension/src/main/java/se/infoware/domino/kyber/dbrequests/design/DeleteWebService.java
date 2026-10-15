package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** Delete one Web Service Provider - DELETE /webservices/{name}?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class DeleteWebService extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);
    WebServices.Entry entry = WebServices.require(db, WebServices.Kind.PROVIDER, name);
    DesignElement el =
        WebServices.asDesignElement(db, entry.document())
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "WebService %s not loadable as DesignElement", entry.name()));
    el.delete();
    request.emit(new JsonObject().put("success", true).put("name", entry.name()));
  }
}
