package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoException;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import org.junit.jupiter.api.Test;

class NsfCreatePolicyTest {

  @Test
  void missingFileMayBeCreated() {
    assertTrue(
        ImportDesignDxl.isMissingDatabase(new DominoException(259, "File does not exist")));
    assertTrue(ImportDesignDxl.isMissingDatabase(new RuntimeException("File not found")));
  }

  @Test
  void otherOpenFailuresAreNotTreatedAsMissing() {
    assertFalse(
        ImportDesignDxl.isMissingDatabase(
            new DominoException(1280, "You are not authorized to perform that operation")));
    assertFalse(ImportDesignDxl.isMissingDatabase(new RuntimeException("database is locked")));
  }

  @Test
  void encryptionDefaultsToNone() throws Exception {
    assertEquals(DominoClient.Encryption.None, ImportDesignDxl.encryptionOf(null));
    assertEquals(DominoClient.Encryption.None, ImportDesignDxl.encryptionOf("  "));
    assertEquals(DominoClient.Encryption.AES256, ImportDesignDxl.encryptionOf("AES-256"));
  }

  @Test
  void knownNsfsGetATitleWhenCreateOmitsDbTitle() {
    assertEquals("Kyber Design Elements", ImportDesignDxl.defaultTitleForNewNsf("kyber.nsf"));
    assertEquals(
        "Kyber Design Elements", ImportDesignDxl.defaultTitleForNewNsf("Server!!kyber.nsf"));
    assertEquals("Kyber Smoke Test", ImportDesignDxl.defaultTitleForNewNsf("kybertest.nsf"));
    assertNull(ImportDesignDxl.defaultTitleForNewNsf("other.nsf"));
  }

  @Test
  void unknownEncryptionIsRejected() {
    assertThrows(KeepExceptionWrongDataRequest.class, () -> ImportDesignDxl.encryptionOf("rot13"));
  }
}
