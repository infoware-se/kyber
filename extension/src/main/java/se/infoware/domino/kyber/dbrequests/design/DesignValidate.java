package se.infoware.domino.kyber.dbrequests.design;

import io.vertx.core.json.JsonObject;
import java.util.Locale;

/**
 * Codes on {@code POST /validate} errors. The message stays the human text; {@code code} is stable
 * for agents.
 */
final class DesignValidate {

  private DesignValidate() {}

  static JsonObject errorRow(int index, JsonObject element, String message) {
    JsonObject row =
        new JsonObject()
            .put("index", index)
            .put("type", element == null ? "" : element.getString("type", ""))
            .put("message", message == null ? "" : message)
            .put("code", codeOf(message));
    if (element != null) {
      String name = element.getString("name");
      if (name != null && !name.isBlank()) {
        row.put("name", name);
      }
    }
    return row;
  }

  static String codeOf(String message) {
    if (message == null || message.isBlank()) {
      return "rejected";
    }
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("elements[") && lower.contains("must be an object")) {
      return "invalid_element";
    }
    if (lower.contains("dxl is required") || lower.contains("dxl must")) {
      return "missing_dxl";
    }
    if (lower.contains("invalid design dxl")
        || (lower.contains("xml") && lower.contains("dxl"))) {
      return "invalid_dxl";
    }
    if (lower.contains("-default-") || lower.contains("localdomainservers")) {
      return "protected_acl";
    }
    if (lower.contains("unknown design") || lower.contains("unsupported type")) {
      return "unknown_type";
    }
    if (lower.contains("name is required")) {
      return "missing_name";
    }
    if (lower.contains("web-inf")) {
      return "webcontent_path";
    }
    if (lower.contains("cannot delete the acl")) {
      return "acl_delete";
    }
    return "rejected";
  }
}
