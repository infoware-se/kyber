package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class DxlImportFixesTest {

  @Test
  void framesetFixIsRegistered() {
    assertTrue(DxlImportFixes.hasAfterImportFix("frameset"));
    assertEquals("frameset_columns_rows", DxlImportFixes.fixIdFor("frameset"));
    assertFalse(DxlImportFixes.describe("frameset").isBlank());
  }

  @Test
  void registeredFixTypesAreKnownBulkTypes() {
    for (Set<String> types : DxlImportFixes.REGISTERED_FIXES.values()) {
      for (String type : types) {
        assertTrue(
            DesignBulkTypes.isKnown(type) || "dbicon".equals(type),
            "fix registered for unknown type: " + type);
      }
    }
  }

  @Test
  void everyBulkTypeIsAccountedForInRoundTripPolicy() {
    // Every API/bulk type is either covered by an afterImport fix or explicitly has none.
    // This fails if a new type is added to DesignBulkTypes without noticing the catalog.
    Set<String> withFix = new TreeSet<>();
    for (Set<String> types : DxlImportFixes.REGISTERED_FIXES.values()) {
      withFix.addAll(types);
    }
    Set<String> all = new TreeSet<>(DesignBulkTypes.REPO_DIR.keySet());
    assertFalse(all.isEmpty());
    for (String type : all) {
      boolean fixed = withFix.contains(type);
      assertEquals(fixed, DxlImportFixes.hasAfterImportFix(type), type);
      if (fixed) {
        assertFalse(DxlImportFixes.fixIdFor(type).isBlank(), type);
      } else {
        assertEquals("", DxlImportFixes.fixIdFor(type), type);
      }
    }
  }
}
