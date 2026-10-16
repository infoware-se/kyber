package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * POST /database — remaps target {@code nsfPath} so KEEP can open a bootstrap DB when the NSF is
 * missing.
 *
 * <p>Caller {@code ?nsfPath=} is the NSF to create. Connection uses {@code KeepConfig.nsf}. The
 * remap does not authorize the create. {@code
 * se.infoware.domino.kyber.dbrequests.design.CreateDatabase} checks Create databases &amp;
 * templates for the logged-in user.
 */
public class CreateDatabase extends DefaultJsonHandler {

  @Override
  protected Future<JsonObject> prepareRequestBody(RoutingContext context, MultiMap headers) {
    return Future.future(
        promise ->
            super.prepareRequestBody(context, headers)
                .onFailure(promise::fail)
                .onSuccess(
                    params -> {
                      String target = params.getString("nsfPath", "");
                      if (target == null || target.isBlank()) {
                        promise.fail(
                            new IllegalArgumentException(
                                "nsfPath query parameter is required (target NSF)"));
                        return;
                      }
                      params.put("targetNsfPath", target.trim());
                      params.put("nsfPath", ImportDesign.BOOTSTRAP_NSF);
                      promise.complete(params);
                    }));
  }
}
