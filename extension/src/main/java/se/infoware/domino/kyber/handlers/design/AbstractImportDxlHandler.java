package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * Shared HTTP handler for design-element create/replace via raw DXL - same pattern as HCL {@code
 * UpdateDxl}.
 *
 * <p>Reads the raw request body as string and puts it on the payload as {@code dxl}. Subclasses are
 * KEEP operationId entry points ({@code CreatePage}, {@code CreateOutline}, ...).
 */
public abstract class AbstractImportDxlHandler extends DefaultJsonHandler {

  @Override
  protected Future<JsonObject> prepareRequestBody(RoutingContext context, MultiMap headers) {
    return Future.future(
        promise ->
            super.prepareRequestBody(context, headers)
                .onFailure(promise::fail)
                .onSuccess(
                    params -> {
                      String dxl = context.body().asString();
                      params.put("dxl", dxl == null ? "" : dxl);
                      promise.complete(params);
                    }));
  }
}
