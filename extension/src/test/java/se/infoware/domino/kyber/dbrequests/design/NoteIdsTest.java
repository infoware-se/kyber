package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
  void fromDxlReadsTheNoteinfoAttribute() {
    // Blank classpath DXL is stripped of noteinfo for create-if-absent imports; parse a live-shaped snippet.
    String withNoteinfo =
        "<imageresource name='$DBIcon'>"
            + "<noteinfo noteid='13a' unid='ABC' sequence='1'></noteinfo>"
            + "</imageresource>";
    assertEquals("13a", NoteIds.fromDxl(withNoteinfo));
    assertNull(NoteIds.fromDxl("<form name='Hello'/>"));
  }
}
