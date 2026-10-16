package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommentUpdateTest {

  @Test
  void aDxlCommentAttributeIsLeftOnTheImportedNote() {
    CommentUpdate.Decision decision =
        CommentUpdate.preserve("Previous").decision("<agent comment='From DXL'></agent>");
    assertFalse(decision.change());
  }

  @Test
  void aMissingCommentRestoresThePreviousNote() {
    CommentUpdate.Decision decision =
        CommentUpdate.preserve("Keep me").decision("<agent name='Order Value'></agent>");
    assertTrue(decision.change());
    assertEquals("Keep me", decision.value());
  }

  @Test
  void aNewNoteWithoutACommentIsLeftBlank() {
    CommentUpdate.Decision decision =
        CommentUpdate.preserve(null)
            .decision("<scriptlibrary name='ProductsLib'></scriptlibrary>");
    assertFalse(decision.change());
  }
}
