package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import org.junit.jupiter.api.Test;
import se.infoware.domino.kyber.dbrequests.design.ListDatabases.DirectoryFileType;

class ListDatabasesTest {

  @Test
  void fileTypeDefaultsToDatabase() throws Exception {
    assertEquals(DirectoryFileType.DATABASE, ListDatabases.fileTypeOf(null));
    assertEquals("database", ListDatabases.fileTypeOf("  ").wire());
    assertEquals(DirectoryFileType.DATABASE, ListDatabases.fileTypeOf("NOTES_DATABASE"));
    assertEquals(DirectoryFileType.DATABASE, ListDatabases.fileTypeOf("1247"));
    assertEquals(DirectoryFileType.TEMPLATE, ListDatabases.fileTypeOf("Template"));
    assertEquals(DirectoryFileType.TEMPLATE, ListDatabases.fileTypeOf("1248"));
    assertEquals(DirectoryFileType.REPLICA_CANDIDATE, ListDatabases.fileTypeOf("replica-candidate"));
    assertEquals(DirectoryFileType.REPLICA_CANDIDATE, ListDatabases.fileTypeOf("1245"));
    assertEquals(DirectoryFileType.TEMPLATE_CANDIDATE, ListDatabases.fileTypeOf("1246"));
    assertEquals("template_candidate", ListDatabases.fileTypeOf("TEMPLATE_CANDIDATE").wire());
  }

  @Test
  void fileTypeRejectsAnythingElse() {
    assertThrows(KeepExceptionWrongDataRequest.class, () -> ListDatabases.fileTypeOf("box"));
    assertThrows(KeepExceptionWrongDataRequest.class, () -> ListDatabases.fileTypeOf("nsf,ntf"));
    assertThrows(KeepExceptionWrongDataRequest.class, () -> ListDatabases.fileTypeOf("database,"));
  }

  @Test
  void extensionsFollowTheNotesDbDirectoryFileTypes() throws Exception {
    DirectoryFileType database = ListDatabases.fileTypeOf(null);
    DirectoryFileType template = ListDatabases.fileTypeOf("template");
    DirectoryFileType any = ListDatabases.fileTypeOf("template_candidate");
    assertTrue(ListDatabases.matchesFileType("mail/user.nsf", database));
    assertTrue(ListDatabases.matchesFileType("mail/user.nsg", database));
    assertTrue(ListDatabases.matchesFileType("headline.nsh", database));
    assertFalse(ListDatabases.matchesFileType("archive.ntf", database));
    assertFalse(ListDatabases.matchesFileType("mail.box", database));
    assertTrue(ListDatabases.matchesFileType("archive.NTF", template));
    assertFalse(ListDatabases.matchesFileType("apps/app.nsf", template));
    assertTrue(ListDatabases.matchesFileType("apps/app.nsf", any));
    assertTrue(ListDatabases.matchesFileType("archive.ntf", any));
    assertFalse(ListDatabases.matchesFileType("notes.nsf.bak", any));
  }

  @Test
  void replicaCandidateSkipsADatabaseDisabledForReplication() {
    assertFalse(ListDatabases.includeOpened(DirectoryFileType.REPLICA_CANDIDATE, true));
    assertTrue(ListDatabases.includeOpened(DirectoryFileType.REPLICA_CANDIDATE, false));
    assertTrue(ListDatabases.includeOpened(DirectoryFileType.DATABASE, true));
    assertTrue(ListDatabases.includeOpened(DirectoryFileType.TEMPLATE_CANDIDATE, true));
  }

  @Test
  void dataPathKeepsARelativeNsfAndDropsAPhysicalPath() {
    assertEquals("mail/user.nsf", ListDatabases.dataPath("mail\\user.nsf"));
    assertEquals("kybertest.nsf", ListDatabases.dataPath("./kybertest.nsf"));
    assertNull(ListDatabases.dataPath("C:\\Domino\\Data\\secret.nsf"));
    assertNull(ListDatabases.dataPath(null));
  }
}
