package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesignUpsertTest {

  @Test
  void preserveIdentityInjectsExistingNoteinfo() throws Exception {
    String existing =
        "<form name='A'><noteinfo noteid='NT00001234' unid='AABBCCDDEEFF00112233445566778899' sequential='1'/><body/></form>";
    String incoming = "<form name='A' comment='New'><field name='S' type='text' kind='editable'/></form>";
    String out = DesignUpsert.preserveIdentity(incoming, existing);
    assertTrue(out.contains("unid='AABBCCDDEEFF00112233445566778899'"));
    assertTrue(out.contains("comment='New'"));
    assertTrue(out.contains("field name='S'"));
    assertFalse(out.toLowerCase().contains("replicaid="));
  }

  @Test
  void preserveIdentityKeepsMatchingUnidPayload() throws Exception {
    String existing =
        "<form name='A'><noteinfo noteid='NT00001234' unid='AABBCCDDEEFF00112233445566778899'/></form>";
    String incoming =
        "<form name='A' replicaid='DEADBEEF'><noteinfo noteid='NT00001234' unid='AABBCCDDEEFF00112233445566778899'/><body/></form>";
    String out = DesignUpsert.preserveIdentity(incoming, existing);
    assertTrue(out.contains("<body/>"));
    assertFalse(out.toLowerCase().contains("replicaid="));
    assertEquals("AABBCCDDEEFF00112233445566778899", NoteIds.unidFromDxl(out));
  }

  @Test
  void preserveIdentityWithoutExistingStripsIdentity() throws Exception {
    String incoming =
        "<form name='A'><noteinfo noteid='NT00001234' unid='AABBCCDDEEFF00112233445566778899'/></form>";
    String out = DesignUpsert.preserveIdentity(incoming, null);
    assertFalse(out.contains("noteinfo"));
    assertTrue(out.contains("name='A'"));
  }
}
