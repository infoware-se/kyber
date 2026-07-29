package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.ScriptLibrary;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a script library from raw DXL — PUT /scriptlibraries?nsfPath=
 *
 * <p>Empty body: require {@code kind} + {@code name} query params, load starter DXL for that kind,
 * rewrite name, then import/sign.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateScriptLibrary extends AsyncDominoJNXJson {

  private static final String TYPE = "scriptlibrary";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String kindParam = request.incoming.getString("kind");
    String nameParam = request.incoming.getString("name");

    String kind;
    String name;
    String source;

    if (dxl == null || dxl.isBlank()) {
      if (kindParam == null || kindParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest(
            "kind is required when dxl body is empty (lotusscript|javascript|ssjs|java)");
      }
      if (nameParam == null || nameParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest(
            "name is required when dxl body is empty");
      }
      kind = ScriptLibraryKinds.normalize(kindParam);
      name = nameParam.trim();
      JsonObject starter = DesignDefaults.resolveOne(request.session(), TYPE, kind);
      dxl = ScriptLibraryKinds.withName(starter.getString("dxl", ""), name);
      source = "default";
    } else {
      name = DesignDxl.requireRootName(dxl, TYPE);
      kind =
          kindParam != null && !kindParam.isBlank()
              ? ScriptLibraryKinds.normalize(kindParam)
              : ScriptLibraryKinds.detectOrDefault(dxl);
      source = "dxl";
    }

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    ScriptLibrary el =
        db.getDesign()
            .getScriptLibrary(name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "ScriptLibrary %s missing after DXL import; cannot sign", name));
    el.sign();
    el.save();

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE)
            .put("name", name)
            .put("kind", kind)
            .put("source", source)
            .put("log", log));
  }
}
