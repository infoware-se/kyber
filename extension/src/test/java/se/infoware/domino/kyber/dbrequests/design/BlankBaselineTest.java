package se.infoware.domino.kyber.dbrequests.design;

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
      "acl.dxl",
      "applicationproperties.dxl",
      "image-DBIcon.dxl",
      "view-default.dxl",
      "outline-AppOutline.dxl",
      "page-AppTitle.dxl",
      "page-DBLocTitle.dxl",
      "page-OutlinePage.dxl",
      "frameset-ViewFrameset.dxl",
      "frameset-MainFrameset.dxl",
      "manifest.json"
    };
    for (String file : files) {
      try (InputStream in = getClass().getResourceAsStream("/defaults/blank/" + file)) {
        assertNotNull(in, "missing blank baseline " + file);
        assertTrue(in.readAllBytes().length > 0, file + " empty");
      }
    }
  }

  @Test
  void blankShellViewSelectsAll() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/defaults/blank/view-default.dxl")) {
      String dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(dxl.contains("SELECT @All"), dxl.substring(0, Math.min(200, dxl.length())));
      assertTrue(dxl.contains("name='(untitled)'") || dxl.contains("name=\"(untitled)\""));
    }
  }

  @Test
  void blankLaunchOpensMainFrameset() {
    String launch = BlankApplication.notesLaunchFramesetDxl();
    assertTrue(launch.contains("whenopened='openframeset'"));
    assertTrue(launch.contains("frameset='$MainFrameset'"));
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
