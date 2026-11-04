package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignValidateDryRunTest {

  @Test
  void scratchPathStaysBesideTargetFile() {
    assertEquals(
        "apps/kyber_vdry_abcdefghijkl.nsf".length(),
        DesignValidateDryRun.scratchPathBeside("apps/demo.nsf").length());
    String path = DesignValidateDryRun.scratchPathBeside("apps/demo.nsf");
    assertTrue(path.startsWith("apps/kyber_vdry_"));
    assertTrue(path.endsWith(".nsf"));
  }

  @Test
  void scratchPathKeepsServerBangBang() {
    String path = DesignValidateDryRun.scratchPathBeside("MailServer!!mail/demo.nsf");
    assertTrue(path.startsWith("MailServer!!mail/kyber_vdry_"));
    assertTrue(path.endsWith(".nsf"));
  }

  @Test
  void appendWarningsReadsXmlWarningTags() {
    JsonArray warnings = new JsonArray();
    DesignValidateDryRun.appendWarnings(
        warnings,
        1,
        new JsonObject().put("type", "form").put("name", "Contact"),
        "<DXLImporterLog><warning>Unknown token foo</warning></DXLImporterLog>");
    assertEquals(1, warnings.size());
    JsonObject row = warnings.getJsonObject(0);
    assertEquals(1, row.getInteger("index"));
    assertEquals("form", row.getString("type"));
    assertEquals("Contact", row.getString("name"));
    assertEquals("Unknown token foo", row.getString("message"));
  }

  @Test
  void appendWarningsReadsPlainTextWarningLines() {
    JsonArray warnings = new JsonArray();
    DesignValidateDryRun.appendWarnings(
        warnings, 0, new JsonObject().put("type", "view"), "Warning: replica id ignored\nOK");
    assertEquals(1, warnings.size());
    assertEquals("Warning: replica id ignored", warnings.getJsonObject(0).getString("message"));
  }
}
