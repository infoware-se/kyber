package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DesignPayloadTest {

  @Test
  void starterAgentExposesLotusScript() throws Exception {
    String dxl = new String(getClass().getResourceAsStream("/defaults/agent.dxl").readAllBytes(), StandardCharsets.UTF_8);
    JsonObject out = DesignPayload.note("Default-LotusScriptAgent", dxl);
    assertEquals("lotusscript", out.getString("kind"));
    assertFalse(out.containsKey("comment"));
    assertFalse(out.containsKey("code"));
    assertTrue(out.getString("dxl").contains("Option Declare"));
    assertNull(out.getString("fileName"));
  }

  @Test
  void starterXPageExposesMarkup() throws Exception {
    String dxl = new String(getClass().getResourceAsStream("/defaults/xpage.dxl").readAllBytes(), StandardCharsets.UTF_8);
    JsonObject out = DesignPayload.note("Default-XPage.xsp", dxl);
    assertEquals("newxpage.xsp", out.getString("fileName"));
    assertFalse(out.getString("dxl").isBlank());
    assertFalse(out.containsKey("code"));
    assertNull(out.getString("kind"));
  }

  @Test
  void classFileOmitsCode() {
    String dxl =
        """
        <note class='form'>
        <item name='$TITLE'><text>WEB-INF/classes/xsp/Activator.class</text></item>
        <item name='$FileNames'><text>WEB-INF/classes/xsp/Activator.class</text></item>
        <item name='$FileData'><rawitemdata type='1'>AQID</rawitemdata></item>
        </note>
        """;
    JsonObject out = DesignPayload.note("WEB-INF/classes/xsp/Activator.class", dxl);
    assertEquals("WEB-INF/classes/xsp/Activator.class", out.getString("fileName"));
    assertFalse(out.containsKey("code"));
  }

  @Test
  void enrichLeavesKindAndSource() throws Exception {
    String dxl = new String(getClass().getResourceAsStream("/defaults/agent.dxl").readAllBytes(), StandardCharsets.UTF_8);
    JsonObject out = new JsonObject().put("kind", "kept").put("source", "dxl");
    DesignPayload.enrich(out, dxl);
    assertEquals("kept", out.getString("kind"));
    assertEquals("dxl", out.getString("source"));
    assertFalse(out.containsKey("code"));
  }

  @Test
  void projectFileNameAllowsWebInf() throws Exception {
    String dxl =
        """
        <note class='form'>
        <item name='$TITLE'><text>.project</text></item>
        <item name='$FileNames'><text>.project</text></item>
        </note>
        """;
    String named = XPagesResource.withName(dxl, "WEB-INF/faces-config.xml");
    assertTrue(named.contains(">WEB-INF/faces-config.xml</text>"));
    assertFalse(named.contains(">.project</text>"));
  }

  @Test
  void aliasesComeFromThePipedName() {
    String dxl =
        """
        <form name='Smoke|Open|Web'>
        <item name='$TITLE'><text>Smoke</text></item>
        </form>
        """;
    JsonObject out = DesignPayload.note("Smoke", dxl);
    JsonArray aliases = out.getJsonArray("aliases");
    assertEquals("Open", aliases.getString(0));
    assertEquals("Web", aliases.getString(1));
  }

  @Test
  void noteExposesUnidFromNoteinfo() {
    String dxl =
        """
        <form name='Smoke'>
        <noteinfo noteid='368a' unid='aabbccddeeff00112233445566778899'/>
        </form>
        """;
    JsonObject out = DesignPayload.note("Smoke", dxl);
    assertEquals("368a", out.getString("noteId"));
    assertEquals("AABBCCDDEEFF00112233445566778899", out.getString("unid"));
  }
}
