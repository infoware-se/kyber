package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a Code &gt; Java source from raw DXL - PUT /javasources?nsfPath=
 *
 * <p>Empty body: require {@code kind} + {@code name}, load starter DXL, rewrite name, import/sign.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateJavaSource extends AsyncDominoJNXJson {

  private static final String TYPE = "javasource";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String kindParam = request.incoming.getString("kind");
    String nameParam = request.incoming.getString("name");

    String kind;
    String source;

    if (dxl == null || dxl.isBlank()) {
      if (kindParam == null || kindParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest(
            "kind is required when dxl body is empty (class|interface|enum)");
      }
      if (nameParam == null || nameParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest("name is required when dxl body is empty");
      }
      kind = JavaSourceKinds.normalize(kindParam);
      String name = nameParam.trim();
      JsonObject starter = DesignDefaults.resolveOne(request.session(), TYPE, kind);
      dxl = JavaSourceKinds.withName(starter.getString("dxl", ""), name);
      source = "default";
    } else {
      kind =
          kindParam != null && !kindParam.isBlank()
              ? JavaSourceKinds.normalize(kindParam)
              : JavaSourceKinds.detectOrDefault(dxl);
      source = "dxl";
    }

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String[] result =
        ResidualDesign.importAndSign(db, importer, TYPE, dxl, "note", "fileresource");
    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE)
            .put("name", result[0])
            .put("kind", kind)
            .put("source", source)
            .put("log", result[1]));
  }
}