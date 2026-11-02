package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class AliasCollisionFixTest {

  @Test
  void withAliasSetsAndClearsRootAttribute() {
    String dxl =
        "<?xml version='1.0'?><frameset name='MainFrameset Notes' alias='MainFrameset'></frameset>";
    String renamed = DesignDxl.withAlias(dxl, "MainFrameset Notes");
    assertEquals("MainFrameset Notes", DesignDxl.rootAlias(renamed));
    assertTrue(renamed.contains("name='MainFrameset Notes'"));
    String cleared = DesignDxl.withAlias(renamed, "");
    assertEquals(null, DesignDxl.rootAlias(cleared));
    assertTrue(!cleared.contains("alias="));
  }

  @Test
  void prepareBatchUniquifiesSharedAliasKeepingOneOriginal() {
    JsonObject notes =
        new JsonObject()
            .put("type", "frameset")
            .put(
                "name",
                "MainFrameset Notes")
            .put(
                "dxl",
                "<frameset name='MainFrameset Notes' alias='MainFrameset'></frameset>");
    JsonObject web =
        new JsonObject()
            .put("type", "frameset")
            .put("name", "MainFramesetWeb")
            .put(
                "dxl",
                "<frameset name='MainFramesetWeb' alias='MainFrameset'></frameset>");
    JsonObject border =
        new JsonObject()
            .put("type", "frameset")
            .put("name", "BorderFrame")
            .put("dxl", "<frameset name='BorderFrame' alias='BorderFrame'></frameset>");

    List<JsonObject> out = AliasCollisionFix.prepareBatch(List.of(notes, web, border));
    assertEquals(3, out.size());
    assertEquals("MainFrameset", DesignDxl.rootAlias(out.get(0).getString("dxl")));
    assertEquals("MainFramesetWeb", DesignDxl.rootAlias(out.get(1).getString("dxl")));
    assertEquals("BorderFrame", DesignDxl.rootAlias(out.get(2).getString("dxl")));
    assertNotEquals(
        DesignDxl.rootAlias(out.get(0).getString("dxl")),
        DesignDxl.rootAlias(out.get(1).getString("dxl")));
  }

  @Test
  void prepareBatchLeavesUniqueAliasesAlone() {
    JsonObject a =
        new JsonObject()
            .put("type", "frameset")
            .put("dxl", "<frameset name='A' alias='AliasA'></frameset>");
    JsonObject b =
        new JsonObject()
            .put("type", "frameset")
            .put("dxl", "<frameset name='B' alias='AliasB'></frameset>");
    List<JsonObject> out = AliasCollisionFix.prepareBatch(List.of(a, b));
    assertEquals("AliasA", DesignDxl.rootAlias(out.get(0).getString("dxl")));
    assertEquals("AliasB", DesignDxl.rootAlias(out.get(1).getString("dxl")));
  }
}
