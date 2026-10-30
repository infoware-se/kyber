package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Set the database title without rewriting application-properties DXL. PUT
 * /database?nsfPath=&amp;dbTitle=
 *
 * <p>The NSF must already exist and have catalog category KyberEnabled. Launch settings stay on
 * the icon note / application properties; this call only changes the title Domino shows for the
 * file.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class UpdateDatabaseTitle extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String title = titleOf(request.incoming);
    if (title == null) {
      throw new KeepExceptionWrongDataRequest("dbTitle is required");
    }
    Database db = request.db();
    db.setTitle(title);
    String path = request.incoming.getString("nsfPath", db.getRelativeFilePath());
    request.emit(
        ReplicaIds.put(
            new JsonObject()
                .put("success", true)
                .put("nsfPath", path == null ? "" : path)
                .put("dbTitle", title),
            db));
  }

  private static String titleOf(JsonObject incoming) {
    if (incoming == null) {
      return null;
    }
    String fromQuery = blankToNull(incoming.getString("dbTitle"));
    if (fromQuery != null) {
      return fromQuery;
    }
    JsonObject body = incoming.getJsonObject("body");
    if (body != null) {
      String fromBody = blankToNull(body.getString("dbTitle"));
      if (fromBody != null) {
        return fromBody;
      }
    }
    return blankToNull(incoming.getString("title"));
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
