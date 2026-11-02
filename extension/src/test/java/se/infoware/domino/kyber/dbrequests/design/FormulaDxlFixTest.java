package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FormulaDxlFixTest {

  @Test
  void stripsUnmatchedDntBegin() {
    String dxl =
        """
        <form name='log'>
        <code event='hidewhen'><formula>REM {DNT Begin}; Pathname = ""</formula></code>
        <code event='hidewhen'><formula>REM {DNT Begin}; (X=""); REM {DNT End};</formula></code>
        </form>
        """;
    String fixed = FormulaDxlFix.prepareIncomingDxl(dxl);
    assertTrue(fixed.contains("Pathname = \"\""));
    assertFalse(fixed.contains("REM {DNT Begin}; Pathname"));
    assertTrue(fixed.contains("REM {DNT Begin}; (X=\"\"); REM {DNT End};"));
    assertEquals(fixed, FormulaDxlFix.prepareIncomingDxl(fixed));
  }
}
