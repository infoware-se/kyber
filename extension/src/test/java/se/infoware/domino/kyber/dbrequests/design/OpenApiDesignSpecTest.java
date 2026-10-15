package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenApiDesignSpecTest {

  private static JsonObject loadSpec() throws Exception {
    try (InputStream in =
        OpenApiDesignSpecTest.class.getResourceAsStream("/openapi/openapi.kyber.json")) {
      assertNotNull(in, "openapi.kyber.json missing from test classpath");
      return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void openApiParsesAndDefines404NotFound() throws Exception {
    JsonObject root = loadSpec();
    assertTrue(root.containsKey("paths"));
    assertTrue(root.containsKey("components"));
    JsonObject responses = root.getJsonObject("components").getJsonObject("responses");
    assertNotNull(responses.getJsonObject("404NotFound"));
    assertNotNull(responses.getJsonObject("400BadRequest"));
    assertTrue(root.getJsonObject("paths").containsKey("/applicationproperties"));
    assertTrue(root.getJsonObject("paths").containsKey("/dbicon"));
    assertFalse(root.getJsonObject("paths").containsKey("/xpagesproject"));
    assertTrue(root.getJsonObject("paths").containsKey("/defaults/{type}"));
    JsonObject export = root.getJsonObject("paths").getJsonObject("/export");
    assertNotNull(export.getJsonObject("get").getJsonObject("responses").getJsonObject("200"));
    JsonObject getProps =
        export.getJsonObject("get")
            .getJsonObject("responses")
            .getJsonObject("200")
            .getJsonObject("content")
            .getJsonObject("application/json")
            .getJsonObject("schema")
            .getJsonObject("properties");
    assertNotNull(getProps.getJsonObject("skipped"));
    JsonObject imp = root.getJsonObject("paths").getJsonObject("/import");
    JsonObject putProps =
        imp.getJsonObject("put")
            .getJsonObject("responses")
            .getJsonObject("200")
            .getJsonObject("content")
            .getJsonObject("application/json")
            .getJsonObject("schema")
            .getJsonObject("properties");
    assertNotNull(putProps.getJsonObject("failed"));
    boolean hasEncryption = false;
    for (Object p : imp.getJsonArray("parameters")) {
      if (p instanceof JsonObject param && "encryption".equals(param.getString("name"))) {
        hasEncryption = true;
      }
    }
    assertTrue(hasEncryption);
  }

  @Test
  void alwaysPresentHaveGetPutOnly() throws Exception {
    JsonObject paths = loadSpec().getJsonObject("paths");
    for (String path : new String[] {"/applicationproperties", "/dbicon"}) {
      JsonObject ops = paths.getJsonObject(path);
      assertNotNull(ops.getJsonObject("get"), path + " missing GET");
      assertNotNull(ops.getJsonObject("put"), path + " missing PUT");
      assertFalse(ops.containsKey("post"), path + " must not expose POST");
      assertFalse(ops.containsKey("delete"), path + " must not expose DELETE");
    }
  }

  @Test
  void kindedCollectionsExposeListCreateAndItemCrud() throws Exception {
    JsonObject paths = loadSpec().getJsonObject("paths");
    for (String base : new String[] {"/agents", "/scriptlibraries", "/javasources"}) {
      assertNotNull(paths.getJsonObject(base).getJsonObject("get"));
      assertNotNull(paths.getJsonObject(base).getJsonObject("post"));
      JsonObject item = paths.getJsonObject(base + "/{name}");
      assertNotNull(item.getJsonObject("get"));
      assertNotNull(item.getJsonObject("put"));
      assertNotNull(item.getJsonObject("delete"));
    }
  }
}
