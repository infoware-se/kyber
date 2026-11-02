package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class RequestParamsTest {

  @Test
  void optionalNameDecodesAndTrimsBlankToNull() {
    assertNull(RequestParams.optionalName(new JsonObject()));
    assertNull(RequestParams.optionalName(new JsonObject().put("name", "  ")));
    assertEquals(
        "(SignatureOption)",
        RequestParams.optionalName(
            new JsonObject().put("name", "%28SignatureOption%29")));
  }

  @Test
  void requireTypeAcceptsNamedApiTypesOnly() throws Exception {
    assertEquals(
        "subform",
        RequestParams.requireType(new JsonObject().put("type", "subform")));
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () -> RequestParams.requireType(new JsonObject().put("type", "about")));
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () -> RequestParams.requireType(new JsonObject().put("type", "designnote")));
  }
}
