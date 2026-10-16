package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;

/**
 * Shared HTTP handler for design-element create/replace.
 *
 * <p>Reads the raw request body. {@code text/xml} is DXL. {@code text/plain} and JSON {@code
 * {"code":"..."}} are the readable text for a note with one body. JSON with {@code dxl} is the same
 * object a get returns. Subclasses are KEEP operationId entry points ({@code CreatePage}, {@code
 * CreateOutline}, ...). The database request, not this handler, enforces the target NSF ACL for
 * the logged-in user.
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
                      DesignWriteBody.apply(
                          params, context.body().asString(), isPlainText(context));
                      promise.complete(params);
                    }));
  }

  private static boolean isPlainText(RoutingContext context) {
    String type = context.request().getHeader("Content-Type");
    return type != null && type.toLowerCase(Locale.ROOT).contains("text/plain");
  }
}
