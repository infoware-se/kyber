package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * GET /databases — opens a database KEEP already allows this user to read.
 *
 * <p>When {@code nsfPath} is omitted, the connection uses {@code KeepConfig.nsf}. A caller who
 * cannot read that file passes {@code nsfPath} of a database they can open. Neither choice
 * filters the list and neither grants access to any other database.
 */
public class ListDatabases extends DefaultJsonHandler {

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
                        params.put("nsfPath", ImportDesign.BOOTSTRAP_NSF);
                      }
                      promise.complete(params);
                    }));
  }
}
