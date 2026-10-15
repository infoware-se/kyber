package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DesignCommentTest {

  @Test
  void emptyCommentIsOnlyTheKyberPrefix() {
    assertEquals("Kyber 0.24.4 Infoware", DesignComment.apply(null, "0.24.4"));
    assertEquals("Kyber 0.24.4 Infoware", DesignComment.apply("  ", "0.24.4"));
  }

  @Test
  void existingCommentFollowsTheDash() {
    assertEquals(
        "Kyber 0.24.4 Infoware - The Comment",
        DesignComment.apply("The Comment", "0.24.4"));
    assertEquals(
        "Kyber 0.24.4 Infoware - https://help.hcl-software.com/dom_designer/14.5.1/index.html",
        DesignComment.apply(
            "https://help.hcl-software.com/dom_designer/14.5.1/index.html", "0.24.4"));
  }

  @Test
  void aPreviousKyberPrefixIsReplaced() {
    assertEquals(
        "Kyber 0.24.4 Infoware - The Comment",
        DesignComment.apply("Kyber 0.24.3 Infoware - The Comment", "0.24.4"));
    assertEquals(
        "Kyber 0.24.4 Infoware", DesignComment.apply("Kyber 0.22.1 Infoware", "0.24.4"));
  }

  @Test
  void repeatedUpdatesDoNotStackThePrefix() {
    String once = DesignComment.apply("The Comment", "0.24.4");
    assertEquals(once, DesignComment.apply(once, "0.24.4"));
    assertEquals(once, DesignComment.apply(DesignComment.apply(once, "0.24.4"), "0.24.4"));
    assertEquals(
        "Kyber 0.25.0 Infoware - The Comment", DesignComment.apply(once, "0.25.0"));
  }

  @Test
  void anAlreadyStackedPrefixCollapsesToTheOriginalComment() {
    String stacked =
        "Kyber 0.24.4 Infoware - Kyber 0.24.4 Infoware - Kyber 0.24.3 Infoware - The Comment";
    assertEquals("Kyber 0.24.4 Infoware - The Comment", DesignComment.apply(stacked, "0.24.4"));
    assertEquals(
        "Kyber 0.24.4 Infoware",
        DesignComment.apply("Kyber 0.24.4 Infoware - Kyber 0.22.1 Infoware", "0.24.4"));
  }

  @Test
  void aCommentThatOnlyStartsWithKyberIsKept() {
    assertEquals(
        "Kyber 0.24.4 Infoware - Kyber rocks", DesignComment.apply("Kyber rocks", "0.24.4"));
  }

  @Test
  void importedDxlCommentWinsOverAStalePrefix() {
    String dxl = "<databasescript comment='Hello World'></databasescript>";
    assertEquals("Hello World", DesignComment.commentAttribute(dxl));
    assertEquals(
        "Hello World", DesignComment.sourceComment(dxl, "Kyber 0.24.4 Infoware"));
    assertEquals(
        "Kyber 0.24.4 Infoware - Hello World",
        DesignComment.apply(DesignComment.sourceComment(dxl, "Kyber 0.24.4 Infoware"), "0.24.4"));
    assertEquals(
        "Kyber 0.24.4 Infoware",
        DesignComment.sourceComment("<databasescript></databasescript>", "Kyber 0.24.4 Infoware"));
  }

  @Test
  void packagedVersionIsTheCurrentRelease() {
    assertEquals("0.24.4", DesignComment.version());
    String stamped = DesignComment.apply("The Comment", DesignComment.version());
    assertEquals("Kyber 0.24.4 Infoware - The Comment", stamped);
    assertEquals(stamped, DesignComment.apply(stamped, DesignComment.version()));
    assertFalse(DesignComment.version().contains("$"));
  }
}
