package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Shared request parameter helpers. */
final class RequestParams {

  private RequestParams() {}

  /** Optional design name from query or path; URL-decoded. */
  static String optionalName(JsonObject incoming) {
    if (incoming == null) {
      return null;
    }
    String name = incoming.getString("name");
    if (name == null || name.isBlank()) {
      return null;
    }
    return URLDecoder.decode(name, StandardCharsets.UTF_8);
  }

  static String requireName(JsonObject incoming) throws KeepExceptionWrongDataRequest {
    String name = optionalName(incoming);
    if (name == null) {
      throw new KeepExceptionWrongDataRequest("name parameter is required");
    }
    return name;
  }

  /** API type from path {@code /named/{type}} (form, subform, …). */
  static String requireType(JsonObject incoming) throws KeepExceptionWrongDataRequest {
    if (incoming == null) {
      throw new KeepExceptionWrongDataRequest("type path parameter is required");
    }
    String type = incoming.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type path parameter is required");
    }
    String t = DesignBulkTypes.normalize(type);
    if (!DesignBulkTypes.isKnown(t) || DesignBulkTypes.isSingleton(t) || "acl".equals(t)) {
      throw new KeepExceptionWrongDataRequest("Unsupported named type: " + type);
    }
    if ("designnote".equals(t)) {
      throw new KeepExceptionWrongDataRequest("designnote is bulk-only; use /export and /import");
    }
    return t;
  }
}
