package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DesignPayloadTest {

  @Test
  void starterAgentExposesLotusScript() throws Exception {
    String dxl = new String(getClass().getResourceAsStream("/defaults/agent.dxl").readAllBytes(), StandardCharsets.UTF_8);
    JsonObject out = DesignPayload.note("Default-LotusScriptAgent", dxl);
    assertEquals("lotusscript", out.getString("kind"));
    assertFalse(out.containsKey("comment"));
    assertTrue(out.getString("code").contains("Option Declare"));
    assertNull(out.getString("fileName"));
  }

  @Test
  void starterXPageExposesMarkup() throws Exception {
    String dxl = new String(getClass().getResourceAsStream("/defaults/xpage.dxl").readAllBytes(), StandardCharsets.UTF_8);
    JsonObject out = DesignPayload.note("Default-XPage.xsp", dxl);
    assertEquals("newxpage.xsp", out.getString("fileName"));
    assertTrue(out.getString("code").contains("xp:view"));
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
    assertNull(out.getString("code"));
  }

  @Test
  void enrichLeavesKindAndSource() throws Exception {
    String dxl = new String(getClass().getResourceAsStream("/defaults/agent.dxl").readAllBytes(), StandardCharsets.UTF_8);
    JsonObject out = new JsonObject().put("kind", "kept").put("source", "dxl");
    DesignPayload.enrich(out, dxl);
    assertEquals("kept", out.getString("kind"));
    assertEquals("dxl", out.getString("source"));
    assertTrue(out.getString("code").contains("Option Declare"));
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
  void codeRoundTripSplitsLotusScriptEvents() throws Exception {
    String dxl =
        """
        <agent name='Smoke'>
        <code event='options'><lotusscript>Option Declare</lotusscript></code>
        <code event='initialize'><lotusscript>Sub Initialize
        End Sub</lotusscript></code>
        </agent>
        """;
    String code =
        """
        Option Public
        Option Declare

        Sub Initialize
        Print 1
        End Sub
        """;
    String written = DesignCode.apply(dxl, code, "agent");
    String read = DesignPayload.note("Smoke", written).getString("code");
    assertTrue(read.contains("Option Declare"));
    assertTrue(read.contains("Print 1"));
    assertTrue(written.contains("event='options'"));
    assertTrue(written.contains("event='initialize'"));
    assertTrue(written.indexOf("Option Public") < written.indexOf("Print 1"));
  }

  @Test
  void codeReplacesATextFileAndRefusesABinaryOne() throws Exception {
    String dxl =
        new String(
            getClass().getResourceAsStream("/defaults/xpagesresource.dxl").readAllBytes(),
            StandardCharsets.UTF_8);
    String written = DesignCode.apply(dxl, "kyber-code", "xpagesresource");
    assertEquals("kyber-code", DesignPayload.note(".project", written).getString("code"));
    String binary =
        """
        <note class='form'>
        <item name='$FileNames'><text>WEB-INF/classes/xsp/Activator.class</text></item>
        <item name='$FileData'><rawitemdata type='1'>AQID</rawitemdata></item>
        </note>
        """;
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () -> DesignCode.apply(binary, "nope", "xpagesresource"));
    assertFalse(DesignCode.accepts("form"));
  }

  @Test
  void codeFillsAnEmptyFileElement() throws Exception {
    String dxl = "<fileresource name='A.txt'><filedata>\n</filedata></fileresource>";
    String written = DesignCode.apply(dxl, "hello file", "fileresource");
    assertEquals("hello file", DesignPayload.note("A.txt", written).getString("code"));
  }

  @Test
  void codeFillsAnEmptyWebContentFile() throws Exception {
    String dxl =
        new String(
            getClass().getResourceAsStream("/defaults/webcontent.dxl").readAllBytes(),
            StandardCharsets.UTF_8);
    String written = DesignCode.apply(dxl, "kyber-code", "webcontent");
    assertEquals("kyber-code", DesignPayload.note("Default.txt", written).getString("code"));
    assertTrue(written.contains("<number>10</number>"));
    Matcher raw =
        Pattern.compile("(?is)<rawitemdata\\b[^>]*>(.*?)</rawitemdata>").matcher(written);
    assertTrue(raw.find());
    byte[] bytes = Base64.getMimeDecoder().decode(raw.group(1).replaceAll("\\s", ""));
    assertEquals(52, bytes.length);
    assertEquals(10, bytes[8] & 0xff);
    assertEquals(28, (bytes[26] & 0xff) | ((bytes[27] & 0xff) << 8));
    assertEquals(0, (bytes[28] & 0xff) | ((bytes[29] & 0xff) << 8));
    assertEquals(10, (bytes[30] & 0xff) | ((bytes[31] & 0xff) << 8));
    assertEquals("kyber-code", new String(bytes, 42, 10, StandardCharsets.UTF_8));
  }
}
