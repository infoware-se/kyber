package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import org.junit.jupiter.api.Test;

class KindAdaptersTest {

  @Test
  void agentNormalizeAndDetect() throws Exception {
    assertEquals(AgentKinds.LOTUSSCRIPT, AgentKinds.normalize(null));
    assertEquals(AgentKinds.FORMULA, AgentKinds.normalize("formula"));
    assertEquals(AgentKinds.IMPORTEDJAVA, AgentKinds.normalize("imported-java"));
    assertEquals("-importedjava", AgentKinds.embeddedSuffix("importedjava"));
    assertEquals("Default-JavaAgent", AgentKinds.defaultName("java"));

    assertEquals(AgentKinds.IMPORTEDJAVA, AgentKinds.detect("<javaproject imported='true'>"));
    assertEquals(AgentKinds.JAVA, AgentKinds.detect("<javaproject name='x'>"));
    assertEquals(AgentKinds.FORMULA, AgentKinds.detect("<simpleaction action='runformula'/>"));
    assertEquals(AgentKinds.SIMPLEACTION, AgentKinds.detect("<simpleaction action='sendmail'/>"));
    assertEquals(AgentKinds.LOTUSSCRIPT, AgentKinds.detect("<lotusscript>Sub Init</lotusscript>"));
    assertNull(AgentKinds.detect("<agent name='x'/>"));
    assertThrows(KeepExceptionWrongDataRequest.class, () -> AgentKinds.normalize("cobol"));
  }

  @Test
  void scriptLibraryNormalizeAndDetect() throws Exception {
    assertEquals(ScriptLibraryKinds.LOTUSSCRIPT, ScriptLibraryKinds.normalize(""));
    assertEquals(ScriptLibraryKinds.JAVASCRIPT, ScriptLibraryKinds.normalize("clientjs"));
    assertEquals(ScriptLibraryKinds.SSJS, ScriptLibraryKinds.normalize("server"));
    assertEquals("-ssjs", ScriptLibraryKinds.embeddedSuffix("ssjs"));

    assertEquals(ScriptLibraryKinds.JAVA, ScriptLibraryKinds.detect("<javaproject/>"));
    assertEquals(
        ScriptLibraryKinds.SSJS, ScriptLibraryKinds.detect("$ServerJavaScriptLibrary for='server'"));
    assertEquals(ScriptLibraryKinds.JAVASCRIPT, ScriptLibraryKinds.detect("<javascript for='web'/>"));
    assertEquals(ScriptLibraryKinds.LOTUSSCRIPT, ScriptLibraryKinds.detectOrDefault(""));
  }

  @Test
  void javaSourceNormalizeDetectAndRewrite() throws Exception {
    assertEquals(JavaSourceKinds.CLASS, JavaSourceKinds.normalize(null));
    assertEquals(JavaSourceKinds.INTERFACE, JavaSourceKinds.normalize("iface"));
    assertEquals(JavaSourceKinds.ENUM, JavaSourceKinds.normalize("enumeration"));
    assertEquals("-interface", JavaSourceKinds.embeddedSuffix("interface"));
    assertEquals("se/infoware/kyber/NewJavaEnum.java", JavaSourceKinds.defaultName("enum"));

    assertEquals(
        JavaSourceKinds.INTERFACE,
        JavaSourceKinds.detect("<item name='$TITLE'><text>se/x/FooInterface.java</text></item>"));
    assertEquals(
        JavaSourceKinds.ENUM,
        JavaSourceKinds.detect("<item name='$TITLE'><text>se/x/ColorEnum.java</text></item>"));
    assertEquals(JavaSourceKinds.CLASS, JavaSourceKinds.detectOrDefault(""));

    String rewritten =
        JavaSourceKinds.rewriteJavaSource(
            "package old.pkg;\r\n\r\npublic class OldName {\r\n}\r\n",
            "se.infoware.kyber",
            "NewName");
    assertTrue(rewritten.contains("package se.infoware.kyber;"));
    assertTrue(rewritten.contains("public class NewName"));
    assertThrows(
        KeepExceptionWrongDataRequest.class, () -> JavaSourceKinds.withName("<note/>", "NoExt"));
  }

  @Test
  void agentWithNameUpdatesRoot() throws Exception {
    String dxl =
        """
        <?xml version='1.0'?>
        <!DOCTYPE agent SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
        <agent name='Old' xmlns='http://www.lotus.com/dxl'>
        <lotusscript>Option Public</lotusscript>
        </agent>
        """;
    String out = AgentKinds.withName(dxl, "RenamedAgent");
    assertTrue(out.contains("name=\"RenamedAgent\"") || out.contains("name='RenamedAgent'"));
    assertTrue(out.toLowerCase().contains("<!doctype agent"));
  }
}
