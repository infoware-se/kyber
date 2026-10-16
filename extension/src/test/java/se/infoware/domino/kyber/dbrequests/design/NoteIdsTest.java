package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NoteIdsTest {

  @Test
  void formatUsesTheNotesNtForm() {
    assertNull(NoteIds.format(0));
    assertEquals("368a", NoteIds.format(0x368A));
    assertEquals("13a", NoteIds.format(0x13A));
    assertEquals("ffffffff", NoteIds.format(-1));
  }

  @Test
  void fromDxlReadsTheNoteinfoAttribute() throws Exception {
    String icon =
        new String(
            getClass().getResourceAsStream("/defaults/blank/image-DBIcon.dxl").readAllBytes(),
            StandardCharsets.UTF_8);
    assertEquals("13a", NoteIds.fromDxl(icon));
    assertNull(NoteIds.fromDxl("<form name='Hello'/>"));
  }
}
