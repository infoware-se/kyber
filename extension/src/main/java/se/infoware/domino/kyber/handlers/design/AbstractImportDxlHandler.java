package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;

/**
 * Shared HTTP handler for design-element create/replace via raw DXL - same pattern as HCL {@code
 * UpdateDxl}.
 *
 * <p>Reads the raw request body as string and puts it on the payload as {@code dxl}. A {@code
 * text/plain} body, or JSON {@code {"code":"..."}}, is the readable text for a note with one body.
 * Subclasses are KEEP operationId entry points ({@code CreatePage}, {@code CreateOutline}, ...).
 * The database request, not this handler, enforces the target NSF ACL for the logged-in user.
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
                      String raw = context.body().asString();
                      if (raw == null) {
                        raw = "";
                      }
                      if (isPlainText(context)) {
                        params.put("code", raw);
                        params.put("dxl", "");
                      } else if (!putJsonCode(params, raw)) {
                        params.put("dxl", raw);
                      }
                      promise.complete(params);
                    }));
  }

  private static boolean isPlainText(RoutingContext context) {
    String type = context.request().getHeader("Content-Type");
    return type != null && type.toLowerCase(Locale.ROOT).contains("text/plain");
  }

  /** JSON {@code {"code":"..."}} without a DXL field. Returns true when that shape was stored. */
  private static boolean putJsonCode(JsonObject params, String raw) {
    String trimmed = raw.stripLeading();
    if (!trimmed.startsWith("{")) {
      return false;
    }
    try {
      JsonObject body = new JsonObject(raw);
      if (!body.containsKey("code") || body.containsKey("dxl")) {
        return false;
      }
      String code = body.getString("code");
      params.put("code", code == null ? "" : code);
      params.put("dxl", "");
      return true;
    } catch (Exception ex) {
      return false;
    }
  }
}
