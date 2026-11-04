package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;

/**
 * PUT /upsert?nsfPath=&type= — create-or-replace one design note from DXL on an existing NSF.
 * Body is raw DXL ({@code text/xml}) or JSON {@code {type?, name?, dxl}}.
 */
@MinimumAcl(AclLevel.DESIGNER)
public class UpsertDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    ImportDesign.ensureEmptyDefaultsNsf(request.session(), DominoClient.Encryption.None);
    request.emit(DesignUpsert.upsert(request.session(), request.db(), request.incoming));
  }
}
