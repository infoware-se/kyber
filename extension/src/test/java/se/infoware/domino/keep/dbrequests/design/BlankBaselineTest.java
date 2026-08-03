package se.infoware.domino.keep.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reference DXL under defaults/blank/ — not create starters, but must ship with the JAR. */
class BlankBaselineTest {

  @Test
  void blankBaselineResourcesExist() throws Exception {
    String[] files = {
      "acl.dxl", "applicationproperties.dxl", "image-DBIcon.dxl", "view-default.dxl", "manifest.json"
    };
    for (String file : files) {
      try (InputStream in = getClass().getResourceAsStream("/defaults/blank/" + file)) {
        assertNotNull(in, "missing blank baseline " + file);
        assertTrue(in.readAllBytes().length > 0, file + " empty");
      }
    }
  }

  @Test
  void blankAclLooksLikeAclDxl() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/defaults/blank/acl.dxl")) {
      String dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(
          dxl.contains("aclentry") || dxl.toLowerCase(java.util.Locale.ROOT).contains("<!doctype acl"),
          "unexpected ACL DXL: " + dxl.substring(0, Math.min(120, dxl.length())));
    }
  }
}
