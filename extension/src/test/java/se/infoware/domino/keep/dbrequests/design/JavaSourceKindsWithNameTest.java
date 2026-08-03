package se.infoware.domino.keep.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class JavaSourceKindsWithNameTest {

  private static final Pattern RAW =
      Pattern.compile(
          "<rawitemdata[^>]*>\\s*([A-Za-z0-9+/=\\s]+)\\s*</rawitemdata>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  @Test
  void withNameRewritesEmbeddedStarterPackageAndType() throws Exception {
    String dxl;
    try (InputStream in = getClass().getResourceAsStream("/defaults/javasource.dxl")) {
      dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    String out = JavaSourceKinds.withName(dxl, "com/example/HelloWorld.java");
    assertTrue(out.contains(">com/example/HelloWorld.java<"));
    assertFalse(out.toLowerCase().contains("$classdata0"), "compiled class items should be stripped");
    assertFalse(out.toLowerCase().contains("$classsize0"));

    Matcher m = RAW.matcher(out);
    assertTrue(m.find(), "$FileData missing");
    byte[] raw = Base64.getDecoder().decode(m.group(1).replaceAll("\\s+", ""));
    assertTrue(raw.length > 42);
    String source = new String(raw, 42, raw.length - 42, StandardCharsets.UTF_8);
    assertTrue(source.contains("package com.example;"));
    assertTrue(source.contains("public class HelloWorld"));
  }

  @Test
  void detectReadsClassFromEmbeddedStarter() throws Exception {
    String dxl;
    try (InputStream in = getClass().getResourceAsStream("/defaults/javasource.dxl")) {
      dxl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(JavaSourceKinds.CLASS.equals(JavaSourceKinds.detect(dxl)));
  }
}
