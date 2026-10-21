package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkspaceIconTest {

  @Test
  void pictureReplacesTheIconAndKeepsLaunchSettings() {
    String icon =
        """
        <iconnote xmlns='http://www.lotus.com/dxl'>
        <noteinfo noteid='ffff0010' unid='AABBCCDDEEFF00112233445566778899'/>
        <png>OLD</png>
        <launchsettings><noteslaunch whenopened='openframeset'/></launchsettings>
        </iconnote>
        """;
    String image = "<imageresource name='$DBIcon'><png>NEW</png></imageresource>";
    String updated = WorkspaceIcon.withImage(icon, WorkspaceIcon.imageElement(image));
    assertTrue(updated.contains("<png>NEW</png>"));
    assertTrue(!updated.contains("<png>OLD</png>"));
    assertEquals(
        "<launchsettings><noteslaunch whenopened='openframeset'/></launchsettings>",
        WorkspaceIcon.launchSettings(updated));
    assertTrue(updated.contains("unid='AABBCCDDEEFF00112233445566778899'"));
  }

  @Test
  void pictureIsInsertedWhenTheIconNoteHasNone() {
    String icon =
        """
        <note class='icon'>
        <item name='$LaunchFrameset'><text>Main</text></item>
        <launchsettings/>
        </note>
        """;
    String updated = WorkspaceIcon.withImage(icon, "<png>NEW</png>");
    assertTrue(updated.contains("<png>NEW</png>"));
    assertTrue(updated.contains("<item name='$LaunchFrameset'><text>Main</text></item>"));
    assertEquals("<launchsettings/>", WorkspaceIcon.launchSettings(updated));
    assertTrue(updated.trim().endsWith("</note>"));
  }

  @Test
  void imageElementIsAbsentWhenTheResourceHasNoPicture() {
    assertNull(WorkspaceIcon.imageElement("<imageresource name='$DBIcon'/>"));
  }
}
