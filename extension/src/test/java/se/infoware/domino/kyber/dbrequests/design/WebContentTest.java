package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebContentTest {

  @Test
  void webInfIsNotWebContent() {
    assertTrue(WebContent.isWebInfPath("WEB-INF/web.xml"));
    assertTrue(WebContent.isWebInfPath("css/WEB-INF/site.css"));
    assertTrue(WebContent.isWebInfPath("WEB-INF"));
    assertFalse(WebContent.isWebInfPath("css/site.css"));
    assertFalse(WebContent.isWebInfPath("New Text Document.txt"));
    assertTrue(WebContent.isWebContent("css/site.css", "w"));
    assertFalse(WebContent.isWebContent("WEB-INF/web.xml", "w"));
    assertFalse(WebContent.isWebContent("css/site.css", ""));
  }

  @Test
  void withNameRewritesFileNameAndRejectsWebInf() throws Exception {
    String dxl;
    try (InputStream in = getClass().getResourceAsStream("/defaults/webcontent.dxl")) {
      dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String out = WebContent.withName(dxl, "css/site.css");
    assertTrue(out.contains("name='css/site.css'") || out.contains("name=\"css/site.css\""));
    assertTrue(out.contains(">css/site.css<"));
    assertFalse(out.contains("Default.txt"));
    assertTrue(out.contains("<text>w</text>"));
    assertThrows(
        KeepExceptionWrongDataRequest.class, () -> WebContent.withName(dxl, "WEB-INF/web.xml"));
  }
}
