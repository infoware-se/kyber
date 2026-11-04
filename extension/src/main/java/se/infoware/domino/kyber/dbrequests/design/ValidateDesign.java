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
 * <p>The body is the same document as {@code PUT /import}. Nothing is written to the target NSF.
 * Kyber first runs {@link DesignImportCheck}. Elements that pass are then imported into a throwaway
 * scratch NSF (JNX has no validate-only flag) so Domino's importer can reject bad DXL. The response
 * is {@code valid}, {@code errors}, {@code warnings}, and a truncated {@code importerLog} when the
 * importer ran. Each error has {@code index}, {@code type}, {@code message}, {@code code}, and
 * {@code name} when present. Scratch creation needs Create databases &amp; templates; when that
 * privilege is missing the response uses {@code dry_run_unavailable} rather than claiming the DXL
 * is valid.
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
    boolean[] precheckOk = new boolean[elements.size()];
    for (int i = 0; i < elements.size(); i++) {
      Object value = elements.getValue(i);
      if (!(value instanceof JsonObject element)) {
        errors.add(
            DesignValidate.errorRow(i, null, "elements[" + i + "] must be an object"));
        precheckOk[i] = false;
        continue;
      }
      try {
        DesignImportCheck.check(element);
        precheckOk[i] = true;
      } catch (Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        errors.add(DesignValidate.errorRow(i, element, message));
        precheckOk[i] = false;
      }
    }

    DesignValidateDryRun.Result dry =
        DesignValidateDryRun.run(request.session(), request.db(), elements, precheckOk);
    for (int i = 0; i < dry.errors.size(); i++) {
      errors.add(dry.errors.getValue(i));
    }

    JsonObject out =
        new JsonObject()
            .put("valid", errors.isEmpty())
            .put("errors", errors)
            .put("warnings", dry.warnings);
    if (dry.importerLog != null) {
      out.put("importerLog", dry.importerLog);
    }
    request.emit(out);
  }
}
