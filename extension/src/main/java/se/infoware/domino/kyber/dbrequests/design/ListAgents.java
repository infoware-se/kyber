package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/** List names — GET collection path (e.g. /agents)?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class ListAgents extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    request.emit(
        DesignListing.elements(
            "Agents",
            db.getDesign().getAgents(),
            el -> {
              try {
                return AgentKinds.detect(exporter.exportDocument(el.getDocument()));
              } catch (Exception ex) {
                return null;
              }
            }));
  }
}
