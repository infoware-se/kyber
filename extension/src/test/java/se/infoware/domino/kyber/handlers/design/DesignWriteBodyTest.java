package se.infoware.domino.kyber.handlers.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignWriteBodyTest {

  @Test
  void xmlBodyIsDxl() {
    JsonObject params = new JsonObject();
    DesignWriteBody.apply(params, "<form name='Product'></form>");
    assertEquals("<form name='Product'></form>", params.getString("dxl"));
  }

  @Test
  void getEnvelopeSuppliesDxlAndIgnoresACommentField() {
    JsonObject params = new JsonObject().put("name", "Inventory Value");
    String raw =
        "{\"dxl\":\"<agent name='Inventory Value'></agent>\",\"comment\":\"Reports\",\"name\":\"Other\",\"kind\":\"lotusscript\"}";
    DesignWriteBody.apply(params, raw);
    assertEquals("<agent name='Inventory Value'></agent>", params.getString("dxl"));
    assertFalse(params.containsKey("comment"));
    assertEquals("Inventory Value", params.getString("name"));
    assertEquals("lotusscript", params.getString("kind"));
  }
}
