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
      "acl_default.dxl",
      "applicationproperties_default.dxl",
      "image_dbicon_default.dxl",
      "view_default.dxl",
      "outline_default.dxl",
      "page_apptitle_default.dxl",
      "page_dbloc_default.dxl",
      "page_outline_default.dxl",
      "frameset_view_default.dxl",
      "frameset_main_default.dxl",
      "launch_default.dxl",
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
    try (InputStream in = getClass().getResourceAsStream("/defaults/blank/view_default.dxl")) {
      String dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(dxl.contains("SELECT @All"), dxl.substring(0, Math.min(200, dxl.length())));
      assertTrue(
          dxl.contains("name='All Documents'") || dxl.contains("name=\"All Documents\""),
          "blank default view must be named All Documents");
      assertTrue(
          dxl.contains("default='true'") || dxl.contains("default=\"true\""),
          "curated blank view must be Domino default (star)");
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
    try (InputStream in = getClass().getResourceAsStream("/defaults/blank/acl_default.dxl")) {
      String dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(
          dxl.contains("aclentry") || dxl.toLowerCase(java.util.Locale.ROOT).contains("<!doctype acl"),
          "unexpected ACL DXL: " + dxl.substring(0, Math.min(120, dxl.length())));
    }
  }
}
