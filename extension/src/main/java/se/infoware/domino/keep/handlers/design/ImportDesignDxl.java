package se.infoware.domino.keep.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * PUT /designdxl — remaps target {@code nsfPath} so KEEP can open a bootstrap DB when the NSF is
 * missing.
 *
 * <p>Caller {@code ?nsfPath=} is the NSF to restore into. Connection uses {@code KeepConfig.nsf}.
 */
public class ImportDesignDxl extends DefaultJsonHandler {

  static final String BOOTSTRAP_NSF = "KeepConfig.nsf";

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
                      params.put("nsfPath", BOOTSTRAP_NSF);
                      promise.complete(params);
                    }));
  }
}
