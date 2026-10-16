package se.infoware.domino.kyber.handlers.design;

import io.vertx.core.json.JsonObject;

/**
 * Request body for a design create or update.
 *
 * <p>Raw {@code text/xml} is DXL. {@code text/plain} and JSON {@code {"code":"..."}} are the
 * readable text of a note with one body. JSON {@code {"dxl":"..."}} stores that DXL. The design
 * comment is the {@code comment} attribute in the DXL, not a field on the JSON object.
 */
final class DesignWriteBody {

  private DesignWriteBody() {}

  static void apply(JsonObject params, String raw, boolean plainText) {
    if (raw == null) {
      raw = "";
    }
    if (plainText) {
      params.put("code", raw);
      params.put("dxl", "");
      return;
    }
    String trimmed = raw.stripLeading();
    if (trimmed.startsWith("{")) {
      try {
        JsonObject body = new JsonObject(raw);
        if (body.containsKey("dxl")) {
          String dxl = body.getString("dxl");
          params.put("dxl", dxl == null ? "" : dxl);
          putIfBlank(params, "kind", body.getString("kind"));
          putIfBlank(params, "name", body.getString("name"));
          return;
        }
        if (body.containsKey("code") && !body.containsKey("dxl")) {
          String code = body.getString("code");
          params.put("code", code == null ? "" : code);
          params.put("dxl", "");
          return;
        }
      } catch (Exception ignored) {
        // Not a JSON object. The body is DXL that happens to start with '{'.
      }
    }
    params.put("dxl", raw);
  }

  private static void putIfBlank(JsonObject params, String key, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    String current = params.getString(key);
    if (current == null || current.isBlank()) {
      params.put(key, value);
    }
  }
}
