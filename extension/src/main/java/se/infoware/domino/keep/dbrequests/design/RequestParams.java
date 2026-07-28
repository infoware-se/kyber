package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Shared request parameter helpers. */
final class RequestParams {

  private RequestParams() {}

  static String requireName(JsonObject incoming) throws KeepExceptionWrongDataRequest {
    String name = incoming.getString("name");
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name path parameter is required");
    }
    return URLDecoder.decode(name, StandardCharsets.UTF_8);
  }
}
