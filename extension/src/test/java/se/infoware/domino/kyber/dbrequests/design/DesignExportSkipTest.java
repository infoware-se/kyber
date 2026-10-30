package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignExportSkipTest {

  @Test
  void skippedRowsKeepTypeMessageAndOptionalName() {
    JsonArray skipped = new JsonArray();
    DesignElementExport.recordSkip(skipped, "frameset", "Main", new RuntimeException("Optional.empty"));
    DesignElementExport.recordSkip(skipped, "designnote", null, new IllegalStateException("prolog only"));
    assertEquals(2, skipped.size());
    JsonObject named = skipped.getJsonObject(0);
    assertEquals("frameset", named.getString("type"));
    assertEquals("Main", named.getString("name"));
    assertEquals("Optional.empty", named.getString("message"));
    assertTrue(named.fieldNames().contains("type"));
    assertTrue(named.fieldNames().contains("message"));
    JsonObject anonymous = skipped.getJsonObject(1);
    assertEquals("designnote", anonymous.getString("type"));
    assertFalse(anonymous.containsKey("name"));
    assertEquals("prolog only", anonymous.getString("message"));
  }
}
