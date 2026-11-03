package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/** GET /templates — use KeepConfig when nsfPath is omitted. */
public class ListTemplates extends DefaultJsonHandler {

  @Override
  protected Future<JsonObject> prepareRequestBody(RoutingContext context, MultiMap headers) {
    return Future.future(
        promise ->
            super.prepareRequestBody(context, headers)
                .onFailure(promise::fail)
                .onSuccess(
                    params -> {
                      String nsf = params.getString("nsfPath", "");
                      if (nsf == null || nsf.isBlank()) {
                        params.put("nsfPath", ImportDesign.BOOTSTRAP_NSF);
                      }
                      promise.complete(params);
                    }));
  }
}
