package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewDxlFixTest {

  @Test
  void stripsReadersItem() {
    String dxl =
        """
        <view name='X'>
        <item name='$Readers' sign='true' readers='true'><text>[SearchSiteAdmin]</text></item>
        <code event='selection'><formula>SELECT @All</formula></code>
        </view>
        """;
    String fixed = ViewDxlFix.prepareIncomingDxl(dxl);
    assertFalse(fixed.contains("$Readers"));
    assertTrue(fixed.contains("SELECT @All"));
    assertEquals(fixed, ViewDxlFix.prepareIncomingDxl(fixed));
  }
}
