package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PatchDesignTest {

  @Test
  void withCommentSetsAndClears() {
    String dxl = "<form name='F' xmlns='http://www.lotus.com/dxl'><body/></form>";
    String with = PatchDesign.withComment(dxl, "Hello");
    assertTrue(with.contains("comment='Hello'"));
    String cleared = PatchDesign.withComment(with, null);
    assertFalse(cleared.contains("comment="));
  }
}
