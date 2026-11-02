package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class XPagesProjectPolicyTest {

  @Test
  void requiredOnlyForXPageAndJava() {
    assertTrue(XPagesProject.requiredFor("xpage", null));
    assertTrue(XPagesProject.requiredFor("javasource", "java"));
    assertTrue(XPagesProject.requiredFor("agent", AgentKinds.JAVA));
    assertTrue(XPagesProject.requiredFor("agent", AgentKinds.IMPORTEDJAVA));
    assertTrue(XPagesProject.requiredFor("scriptlibrary", ScriptLibraryKinds.JAVA));

    assertFalse(XPagesProject.requiredFor("agent", AgentKinds.LOTUSSCRIPT));
    assertFalse(XPagesProject.requiredFor("agent", AgentKinds.FORMULA));
    assertFalse(XPagesProject.requiredFor("scriptlibrary", ScriptLibraryKinds.LOTUSSCRIPT));
    assertFalse(XPagesProject.requiredFor("scriptlibrary", ScriptLibraryKinds.JAVASCRIPT));
    assertFalse(XPagesProject.requiredFor("form", null));
    assertFalse(XPagesProject.requiredFor("view", null));
  }

  @Test
  void requiredForElementsLooksAtPayload() {
    assertFalse(XPagesProject.requiredForElements(new JsonArray()));
    assertFalse(
        XPagesProject.requiredForElements(
            new JsonArray()
                .add(new JsonObject().put("type", "form").put("dxl", "<form name='a'/>"))));
    assertTrue(
        XPagesProject.requiredForElements(
            new JsonArray()
                .add(new JsonObject().put("type", "xpage").put("dxl", "<xpage name='Home'/>"))));
    assertTrue(
        XPagesProject.requiredForElements(
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("type", "agent")
                        .put("dxl", "<agent name='J'><javaproject name='x'/></agent>"))));
    assertFalse(
        XPagesProject.requiredForElements(
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("type", "agent")
                        .put(
                            "dxl",
                            "<agent name='L'><lotusscript>Sub Initialize\nEnd Sub</lotusscript></agent>"))));
  }
}
