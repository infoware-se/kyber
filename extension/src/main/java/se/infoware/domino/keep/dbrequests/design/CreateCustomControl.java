package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionServer;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * Create/replace a Custom Control from raw DXL - PUT /customcontrols?nsfPath=
 *
 * <p>Designer exports are {@code <note>} (typically {@code $Flags=gC~4;}, {@code $TITLE=*.xsp}).
 * Not the same as JNX {@code CompositeComponent} (Eclipse CAE).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class CreateCustomControl extends AsyncDominoJNXJson {

  private static final String TYPE = "customcontrol";

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String dxl = request.incoming.getString("dxl", "");
    String name = DesignDxl.requireDesignName(dxl, "note", "fileresource");

    Database db = request.db();
    DxlImporter importer = request.session().getDxlImporter();
    String log = DxlDesignPatch.importDesign(importer, dxl, db);

    CustomControls.Entry entry =
        CustomControls.find(db, name)
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "CustomControl %s missing after DXL import; cannot sign", name));
    DesignElement el =
        CustomControls.asDesignElement(db, entry.document())
            .orElseThrow(
                () ->
                    new KeepExceptionServer(
                        "CustomControl %s not loadable as DesignElement after import", name));
    el.sign();
    el.save();

    request.emit(
        new JsonObject()
            .put("success", true)
            .put("type", TYPE)
            .put("name", entry.name())
            .put("log", log));
  }
}
