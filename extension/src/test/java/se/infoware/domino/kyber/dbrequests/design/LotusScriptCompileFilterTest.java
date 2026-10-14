package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LotusScriptCompileFilterTest {

  @Test
  void onlyLotusScriptLibrariesAreCompiled() {
    assertTrue(
        DesignLotusScriptCompile.isLotusScriptDxl(
            "<scriptlibrary><code event='library'><lotusscript>Sub Initialize\nEnd Sub</lotusscript></code></scriptlibrary>"));
    assertFalse(
        DesignLotusScriptCompile.isLotusScriptDxl(
            "<scriptlibrary><code event='library'><javascript>function hello(){return \"hi\";}</javascript></code></scriptlibrary>"));
    assertFalse(
        DesignLotusScriptCompile.isLotusScriptDxl(
            "<scriptlibrary><item name='$ServerJavaScriptLibrary'><text>1</text></item><code event='library' for='server'><javascript>function hello(){return \"hi\";}</javascript></code></scriptlibrary>"));
    assertFalse(
        DesignLotusScriptCompile.isLotusScriptDxl(
            "<scriptlibrary><code event='library'><javaproject><java name='A.java'>class A {}</java></javaproject></code></scriptlibrary>"));
    assertFalse(DesignLotusScriptCompile.isLotusScriptDxl(""));
  }
}
