package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesignCatalogTest {

  @Test
  void categoryKyberEnabledIsAWholeToken() {
    assertFalse(DesignCatalog.contains(null));
    assertFalse(DesignCatalog.contains(""));
    assertFalse(DesignCatalog.contains("  "));
    assertFalse(DesignCatalog.contains("Kyber"));
    assertFalse(DesignCatalog.contains("KyberEnabledExtra"));
    assertTrue(DesignCatalog.contains("KyberEnabled"));
    assertTrue(DesignCatalog.contains("kyberenabled"));
    assertTrue(DesignCatalog.contains("Other, KyberEnabled"));
    assertTrue(DesignCatalog.contains("KyberEnabled; Archive"));
  }
}
