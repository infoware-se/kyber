package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    JsonObject database = root.getJsonObject("paths").getJsonObject("/database");
    assertEquals("createDatabase", database.getJsonObject("post").getString("operationId"));
    assertEquals("deleteDatabase", database.getJsonObject("delete").getString("operationId"));
    JsonObject databases = root.getJsonObject("paths").getJsonObject("/databases");
    assertEquals("listDatabases", databases.getJsonObject("get").getString("operationId"));
    assertNotNull(
        databases
            .getJsonObject("get")
            .getJsonObject("responses")
            .getJsonObject("200")
            .getJsonObject("content")
            .getJsonObject("application/json")
            .getJsonObject("schema")
            .getJsonObject("properties")
            .getJsonObject("more"));
    assertFalse(root.getJsonObject("paths").containsKey("/defaults"));
    assertFalse(root.getJsonObject("paths").containsKey("/defaults/{type}"));
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
    JsonObject exported =
        getProps
            .getJsonObject("elements")
            .getJsonObject("items")
            .getJsonObject("properties");
    assertEquals("368a", exported.getJsonObject("noteId").getString("example"));
    assertEquals(
        "AABBCCDDEEFF00112233445566778899", exported.getJsonObject("unid").getString("example"));
    assertNotNull(root.getJsonObject("paths").getJsonObject("/validate").getJsonObject("post"));
    assertEquals(
        "validateDesign",
        root.getJsonObject("paths")
            .getJsonObject("/validate")
            .getJsonObject("post")
            .getString("operationId"));
    assertEquals("8525812300456789", getProps.getJsonObject("replicaId").getString("example"));
    assertEquals(
        "8525812300456789",
        schemaProps(root.getJsonObject("paths"), "/import", "put")
            .getJsonObject("replicaId")
            .getString("example"));
    assertEquals(
        "8525812300456789",
        schemaProps(root.getJsonObject("paths"), "/databases", "get")
            .getJsonObject("databases")
            .getJsonObject("items")
            .getJsonObject("properties")
            .getJsonObject("replicaId")
            .getString("example"));
    assertEquals(
        "8525812300456789",
        schemaProps(root.getJsonObject("paths"), "/database", "post")
            .getJsonObject("replicaId")
            .getString("example"));
    assertEquals(
        "8525812300456789",
        schemaProps(root.getJsonObject("paths"), "/database", "delete")
            .getJsonObject("replicaId")
            .getString("example"));
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
    assertNotNull(putProps.getJsonObject("results"));
    assertFalse(putProps.containsKey("lotusscript"));
    assertFalse(putProps.containsKey("log"));
    JsonObject resultRow =
        putProps
            .getJsonObject("results")
            .getJsonObject("items")
            .getJsonObject("properties");
    assertFalse(resultRow.containsKey("log"));
    assertFalse(resultRow.containsKey("compiled"));
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
  void responseSchemasDocumentFieldsTheApiReturns() throws Exception {
    JsonObject paths = loadSpec().getJsonObject("paths");
    JsonObject listed = schemaProps(paths, "/forms", "get");
    assertNotNull(listed.getJsonObject("Forms"));
    JsonObject element =
        listed
            .getJsonObject("elements")
            .getJsonObject("items")
            .getJsonObject("properties");
    assertFalse(element.containsKey("fileName"));
    assertNotNull(element.getJsonObject("noteId"));
    assertNotNull(element.getJsonObject("unid"));
    assertNotNull(element.getJsonObject("aliases"));
    assertFalse(element.containsKey("kind"));
    assertFalse(element.containsKey("comment"));

    JsonObject got = schemaProps(paths, "/forms/{name}", "get");
    assertFalse(got.containsKey("code"));
    assertNotNull(got.getJsonObject("noteId"));
    assertNotNull(got.getJsonObject("unid"));
    assertNotNull(got.getJsonObject("aliases"));
    assertFalse(got.containsKey("fileName"));
    assertFalse(got.containsKey("kind"));
    assertFalse(got.containsKey("comment"));

    JsonObject agent =
        schemaProps(paths, "/agents", "get")
            .getJsonObject("elements")
            .getJsonObject("items")
            .getJsonObject("properties")
            .getJsonObject("kind");
    assertEquals("lotusscript,formula,simpleaction,java,importedjava", enumOf(agent));
    assertNotNull(schemaProps(paths, "/agents/{name}", "get").getJsonObject("kind"));
    assertFalse(schemaProps(paths, "/agents/{name}", "get").containsKey("fileName"));
    assertNotNull(
        schemaProps(paths, "/xpages", "get")
            .getJsonObject("elements")
            .getJsonObject("items")
            .getJsonObject("properties")
            .getJsonObject("fileName"));
    assertNotNull(schemaProps(paths, "/xpages/{name}", "get").getJsonObject("fileName"));
    assertNotNull(schemaProps(paths, "/customcontrols/{name}", "get").getJsonObject("fileName"));
    assertFalse(schemaProps(paths, "/fileresources/{name}", "get").containsKey("fileName"));
    assertFalse(schemaProps(paths, "/javasources/{name}", "get").containsKey("fileName"));

    JsonObject source = schemaProps(paths, "/forms", "post").getJsonObject("source");
    assertEquals("dxl,default", enumOf(source));
    assertEquals("DXL stored on the note.", schemaProps(paths, "/forms", "post").getJsonObject("dxl").getString("description"));
    assertFalse(schemaProps(paths, "/forms", "post").containsKey("log"));
    assertFalse(schemaProps(paths, "/forms", "post").containsKey("compiled"));
    assertFalse(schemaProps(paths, "/agents", "post").containsKey("compiled"));
    JsonObject libraryPut = paths.getJsonObject("/scriptlibraries/{name}").getJsonObject("put");
    assertNotNull(
        libraryPut
            .getJsonObject("requestBody")
            .getJsonObject("content")
            .getJsonObject("application/json"));
    JsonObject libraryWritten = schemaProps(paths, "/scriptlibraries/{name}", "put");
    assertFalse(libraryWritten.containsKey("compiled"));
    assertFalse(libraryWritten.containsKey("log"));
    assertFalse(libraryWritten.containsKey("signedBy"));

    assertEquals(
        "updateAcl", paths.getJsonObject("/acl").getJsonObject("put").getString("operationId"));
    assertFalse(schemaProps(paths, "/acl", "put").containsKey("source"));
    assertFalse(schemaProps(paths, "/database", "post").containsKey("source"));
  }

  private static JsonObject schemaProps(JsonObject paths, String path, String method) {
    return paths
        .getJsonObject(path)
        .getJsonObject(method)
        .getJsonObject("responses")
        .getJsonObject("200")
        .getJsonObject("content")
        .getJsonObject("application/json")
        .getJsonObject("schema")
        .getJsonObject("properties");
  }

  private static String enumOf(JsonObject property) {
    StringBuilder out = new StringBuilder();
    for (Object value : property.getJsonArray("enum")) {
      if (out.length() > 0) {
        out.append(',');
      }
      out.append(value);
    }
    return out.toString();
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
