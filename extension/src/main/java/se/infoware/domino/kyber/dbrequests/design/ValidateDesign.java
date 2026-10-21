package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Set;

/**
 * Dry run of a design import. POST /validate?nsfPath=
 *
 * <p>The body is the same document as {@code PUT /import}. Nothing is written, and the response
 * has no importer log. {@code valid} is false when Kyber would reject an element before Domino
 * sees it. A note Domino itself cannot store still fails on the import.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class ValidateDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject payload = request.incoming.getJsonObject("body", request.incoming);
    JsonArray elements = payload.getJsonArray("elements");
    if (elements == null) {
      elements = request.incoming.getJsonArray("elements");
    }
    if (elements == null) {
      throw new KeepExceptionWrongDataRequest("elements array is required");
    }
    if (ImportDesign.requiredWriteLevel(elements) == AclLevel.MANAGER) {
      String path = request.db().getRelativeFilePath();
      request.session().getDatabaseDomino(path, AclLevel.MANAGER, Set.of(), Set.of());
    }

    JsonArray errors = new JsonArray();
    for (int i = 0; i < elements.size(); i++) {
      Object value = elements.getValue(i);
      if (!(value instanceof JsonObject element)) {
        errors.add(
            new JsonObject()
                .put("message", "elements[" + i + "] must be an object"));
        continue;
      }
      try {
        DesignImportCheck.check(element);
      } catch (Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        JsonObject row = new JsonObject().put("type", element.getString("type", "")).put("message", message);
        String name = element.getString("name");
        if (name != null && !name.isBlank()) {
          row.put("name", name);
        }
        errors.add(row);
      }
    }
    request.emit(new JsonObject().put("valid", errors.isEmpty()).put("errors", errors));
  }
}
