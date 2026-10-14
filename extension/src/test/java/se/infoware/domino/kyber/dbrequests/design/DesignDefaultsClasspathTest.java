package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DesignDefaultsClasspathTest {

  @Test
  void normalizeTypeAcceptsPluralAndAliases() throws Exception {
    assertEquals("form", DesignDefaults.normalizeType("forms"));
    assertEquals("image", DesignDefaults.normalizeType("imageresource"));
    assertEquals("about", DesignDefaults.normalizeType("helpaboutdocument"));
    assertThrows(KeepExceptionWrongDataRequest.class, () -> DesignDefaults.normalizeType("nope"));
  }

  @Test
  void defaultNsfPathReadsPropertyOrFallback() {
    String path = DesignDefaults.defaultNsfPath();
    assertFalse(path.isBlank());
  }

  @Test
  void embeddedClasspathCoversKindedStarters() {
    assertTrue(DesignDefaults.classpathDxl("form").isPresent());
    assertTrue(DesignDefaults.classpathDxl("agent", "lotusscript").isPresent());
    assertTrue(DesignDefaults.classpathDxl("agent", "formula").isPresent());
    assertTrue(DesignDefaults.classpathDxl("agent", "simpleaction").isPresent());
    assertTrue(DesignDefaults.classpathDxl("agent", "java").isPresent());
    assertTrue(DesignDefaults.classpathDxl("agent", "importedjava").isPresent());
    String clientJs = DesignDefaults.classpathDxl("scriptlibrary", "javascript").orElseThrow();
    assertEquals("javascript", ScriptLibraryKinds.detect(clientJs));
    String ssjs = DesignDefaults.classpathDxl("scriptlibrary", "ssjs").orElseThrow();
    assertTrue(ssjs.contains("$ServerJavaScriptLibrary"));
    assertFalse(ssjs.contains("for='server'"), "DTD code@for is only web or client");
    assertEquals("ssjs", ScriptLibraryKinds.detect(ssjs));
    assertTrue(ssjs.indexOf("<code") < ssjs.indexOf("<item"), "code must precede items");
    assertTrue(clientJs.indexOf("<code") < clientJs.indexOf("<item"));
    assertTrue(DesignDefaults.classpathDxl("scriptlibrary", "java").isPresent());
    assertTrue(DesignDefaults.classpathDxl("javasource", "class").isPresent());
    assertTrue(DesignDefaults.classpathDxl("javasource", "interface").isPresent());
    assertTrue(DesignDefaults.classpathDxl("javasource", "enum").isPresent());
    assertTrue(DesignDefaults.classpathDxl("xpage").isPresent());
    assertTrue(DesignDefaults.classpathDxl("xspproperties").isPresent());
    assertTrue(DesignDefaults.classpathDxl("xpagesresource").isPresent());
  }

  @Test
  void alwaysPresentSingletonsAreNotEmbeddedStarters() {
    // Always present with NSF — blank baseline only, not create-from-default starters.
    assertTrue(DesignDefaults.classpathDxl("applicationproperties").isEmpty());
    assertTrue(DesignDefaults.classpathDxl("dbicon").isEmpty());
  }

  @Test
  void embeddedTypesIncludesOptionalSingletonsNotAlwaysPresent() {
    Set<String> embedded = DesignDefaults.embeddedTypes();
    assertTrue(embedded.contains("about"));
    assertTrue(embedded.contains("using"));
    assertTrue(embedded.contains("xspproperties"));
    assertTrue(embedded.contains("databasescript"));
    assertFalse(embedded.contains("applicationproperties"));
    assertFalse(embedded.contains("dbicon"));
  }

  @Test
  void xpagesProjectSkeletonResourcesExist() throws Exception {
    String[] files = {
      "project.dxl",
      "classpath.dxl",
      "plugin-xml.dxl",
      "build-properties.dxl",
      "faces-config.dxl",
      "activator-class.dxl",
      "xsp-properties.dxl"
    };
    for (String file : files) {
      String dxl = readResource("/defaults/xpages-project/" + file);
      assertFalse(dxl.contains("Dantooine"), file);
      assertFalse(dxl.contains("onInfoware"), file);
      assertFalse(dxl.contains("Mathias"), file);
    }
    String project = filePayload(readResource("/defaults/xpages-project/project.dxl"));
    String plugin = filePayload(readResource("/defaults/xpages-project/plugin-xml.dxl"));
    String activator = filePayload(readResource("/defaults/xpages-project/activator-class.dxl"));
    String resource = filePayload(DesignDefaults.classpathDxl("xpagesresource").orElseThrow());
    for (String payload : new String[] {project, plugin, activator, resource}) {
      assertFalse(payload.contains("keepdesign"));
      assertFalse(payload.contains("Dantooine"));
      assertFalse(payload.contains("onInfoware"));
    }
    assertTrue(project.contains("<name>kyber.nsf</name>"));
    assertTrue(plugin.contains("id=\"kyber_nsf\""));
    assertTrue(activator.contains("kyber_nsf"));
  }

  private static String readResource(String path) throws Exception {
    try (var in = XPagesProject.class.getResourceAsStream(path)) {
      assertTrue(in != null, "missing " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String filePayload(String dxl) {
    Matcher matcher =
        Pattern.compile("(?is)<rawitemdata[^>]*>\\s*([A-Za-z0-9+/=\\s]+)\\s*</rawitemdata>")
            .matcher(dxl);
    assertTrue(matcher.find(), "missing file payload");
    byte[] raw = Base64.getMimeDecoder().decode(matcher.group(1).replaceAll("\\s+", ""));
    assertTrue(raw.length > 42);
    return new String(raw, 42, raw.length - 42, StandardCharsets.ISO_8859_1);
  }
}
