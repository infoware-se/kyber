package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesignCatalogTest {

  @Test
  void containsRecognizesKyberEnabledAmongOthers() {
    assertTrue(DesignCatalog.contains("KyberEnabled"));
    assertTrue(DesignCatalog.contains("Prod, KyberEnabled; Archive"));
    assertFalse(DesignCatalog.contains("Production"));
    assertFalse(DesignCatalog.contains(null));
    assertFalse(DesignCatalog.contains(""));
  }
}
