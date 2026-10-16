package se.infoware.domino.kyber.handlers.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignWriteBodyTest {

  @Test
  void xmlBodyIsDxl() {
    JsonObject params = new JsonObject();
    DesignWriteBody.apply(params, "<form name='Product'></form>", false);
    assertEquals("<form name='Product'></form>", params.getString("dxl"));
  }

  @Test
  void plainTextIsCode() {
    JsonObject params = new JsonObject();
    DesignWriteBody.apply(params, "Option Public", true);
    assertEquals("Option Public", params.getString("code"));
    assertEquals("", params.getString("dxl"));
  }

  @Test
  void codeJsonStaysTheSingleBodyText() {
    JsonObject params = new JsonObject();
    DesignWriteBody.apply(params, "{\"code\":\"Print \\\"Hi\\\"\"}", false);
    assertEquals("Print \"Hi\"", params.getString("code"));
    assertEquals("", params.getString("dxl"));
  }

  @Test
  void getEnvelopeSuppliesDxlAndIgnoresACommentField() {
    JsonObject params = new JsonObject().put("name", "Inventory Value");
    String raw =
        "{\"dxl\":\"<agent name='Inventory Value'></agent>\",\"comment\":\"Reports\",\"name\":\"Other\",\"kind\":\"lotusscript\"}";
    DesignWriteBody.apply(params, raw, false);
    assertEquals("<agent name='Inventory Value'></agent>", params.getString("dxl"));
    assertFalse(params.containsKey("comment"));
    assertEquals("Inventory Value", params.getString("name"));
    assertEquals("lotusscript", params.getString("kind"));
  }
}
