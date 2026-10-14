package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesignBulkTypesTest {

  @Test
  void singletonsMatchApiContract() {
    assertTrue(DesignBulkTypes.isSingleton("applicationproperties"));
    assertTrue(DesignBulkTypes.isSingleton("dbicon"));
    assertTrue(DesignBulkTypes.isSingleton("about"));
    assertTrue(DesignBulkTypes.isSingleton("using"));
    assertTrue(DesignBulkTypes.isSingleton("xspproperties"));
    assertTrue(DesignBulkTypes.isSingleton("databasescript"));
    assertTrue(DesignBulkTypes.isSingleton("acl"));
    assertFalse(DesignBulkTypes.isSingleton("form"));
    assertFalse(DesignBulkTypes.isSingleton("xpage"));
  }

  @Test
  void normalizeAliases() {
    assertEquals("image", DesignBulkTypes.normalize("imageresource"));
    assertEquals("about", DesignBulkTypes.normalize("helpaboutdocument"));
    assertEquals("using", DesignBulkTypes.normalize("helpusingdocument"));
    assertEquals("stylesheet", DesignBulkTypes.normalize("stylesheetresource"));
  }

  @Test
  void aclImportsAfterDesign() {
    assertTrue(DesignBulkTypes.importOrder("form") < DesignBulkTypes.importOrder("acl"));
    assertTrue(
        DesignBulkTypes.importOrder("applicationproperties")
            < DesignBulkTypes.importOrder("xpage"));
  }
}
