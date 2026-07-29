package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Bulk import design DXL - PUT /designdxl?nsfPath=
 *
 * <p>Target NSF must already exist. This API never creates or deletes databases.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class ImportDesignDxl extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    String nsfPath = request.incoming.getString("nsfPath", "");

    JsonObject payload = request.incoming.getJsonObject("body", request.incoming);
    JsonArray elements = payload.getJsonArray("elements");
    if (elements == null) {
      elements = request.incoming.getJsonArray("elements");
    }
    if (elements == null) {
      throw new KeepExceptionWrongDataRequest("elements array is required");
    }

    DxlImporter importer = request.session().getDxlImporter();

    List<JsonObject> ordered = DesignElementIO.sortForImport(elements);
    JsonArray results = new JsonArray();
    int ok = 0;
    int failed = 0;
    for (JsonObject el : ordered) {
      String type = el.getString("type", "");
      String dxl = el.getString("dxl", "");
      try {
        JsonObject one = DesignElementIO.importOne(importer, db, type, dxl);
        results.add(one);
        ok++;
      } catch (Exception e) {
        failed++;
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        JsonObject row =
            new JsonObject()
                .put("success", false)
                .put("type", type)
                .put("message", message);
        if (el.getString("name") != null) {
          row.put("name", el.getString("name"));
        }
        results.add(row);
      }
    }

    JsonObject compile = DesignLotusScriptCompile.compileAll(db);

    request.emit(
        new JsonObject()
            .put("success", failed == 0)
            .put("nsfPath", nsfPath)
            .put("imported", ok)
            .put("failed", failed)
            .put("results", results)
            .put("lotusscript", compile));
  }
}
