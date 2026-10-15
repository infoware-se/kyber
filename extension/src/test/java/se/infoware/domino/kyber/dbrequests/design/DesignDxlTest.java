package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import org.junit.jupiter.api.Test;

class DesignDxlTest {

  @Test
  void forNewNoteStripsNoteinfoAndReplicaid() throws Exception {
    String dxl =
        """
        <?xml version='1.0'?>
        <!DOCTYPE form SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
        <form name='X' replicaid='0123456789ABCDEF' xmlns='http://www.lotus.com/dxl'>
        <noteinfo unid='AABBCCDDEEFF00112233445566778899' sequence='1'>
        <created><datetime>20240101T120000,00Z</datetime></created>
        </noteinfo>
        <body/>
        </form>
        """;
    String out = DesignDxl.forNewNote(dxl);
    assertFalse(out.toLowerCase().contains("<noteinfo"));
    assertFalse(out.toLowerCase().contains("replicaid="));
    assertTrue(out.contains("name='X'") || out.contains("name=\"X\""));
  }

  @Test
  void roundTripLeavesOutAgentDataAndUntitledViews() {
    assertEquals("agentdata", DesignDxl.rootLocalName("<agentdata><agent agentname='X'/></agentdata>"));
    assertNull(
        DesignElementExport.residualRoundTrip(
            "designnote", "unid:ABC", "<agentdata><agent agentname='Ran'/></agentdata>"));
    assertNull(
        DesignElementExport.residualRoundTrip(
            "designnote", "Hello", "<document form='Hello'><item name='Subject'/></document>"));
    assertNull(
        DesignElementExport.residualRoundTrip("designnote", "unid:ABC", "<view></view>"));
    var named =
        DesignElementExport.residualRoundTrip(
            "designnote", "unid:ABC", "<view name='KeptView'></view>");
    assertEquals("view", named.getString("type"));
    assertEquals("KeptView", named.getString("name"));
    var note =
        DesignElementExport.residualRoundTrip(
            "designnote", "unid:ABC", "<note name='Extra'></note>");
    assertEquals("designnote", note.getString("type"));
    assertEquals("unid:ABC", note.getString("name"));
  }

  @Test
  void formulaClassViewHasNoDesignNameAndStaysOutOfTheRoundTrip() {
    String dxl =
        """
        <?xml version='1.0'?>
        <view xmlns='http://www.lotus.com/dxl' noviewformat='true'>
        <item name='$FormulaClass'><text>1</text></item>
        </view>
        """;
    assertNull(DesignDxl.designName(dxl));
    assertNull(DesignElementExport.residualRoundTrip("view", "$FormulaClass", dxl));
    assertThrows(
        KeepExceptionWrongDataRequest.class, () -> DesignDxl.requireDesignName(dxl, "view"));
  }

  @Test
  void emptyClassIndexIsRebuiltFromThePageClassBytes() {
    String inner = classItem("$ClassData0", "xsp/Newxpage$NewxpagePage");
    String outer = classItem("$ClassData1", "xsp/Newxpage");
    String dxl =
        """
        <note>
        %s
        %s
        <item name='$ClassIndexItem'><textlist/></item>
        </note>
        """
            .formatted(inner, outer);
    String out = DesignDxl.withClassIndex(dxl);
    int page = out.indexOf("<text>WEB-INF/classes/xsp/Newxpage$NewxpagePage.class</text>");
    int host = out.indexOf("<text>WEB-INF/classes/xsp/Newxpage.class</text>");
    assertTrue(page >= 0 && host > page);
    assertFalse(out.contains("xsp/page"));
    assertFalse(out.contains("<textlist/>"));
    String kept =
        dxl.replace(
            "<item name='$ClassIndexItem'><textlist/></item>",
            "<item name='$ClassIndexItem'><textlist><text>WEB-INF/classes/xsp/Keep.class</text></textlist></item>");
    assertEquals(kept, DesignDxl.withClassIndex(kept));
  }

  @Test
  void starterXPageClassFilesRebuildTheIndexInItemOrder() throws Exception {
    String dxl =
        new String(
            DesignDxlTest.class.getResourceAsStream("/defaults/xpage.dxl").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    dxl =
        java.util.regex.Pattern.compile("(?s)<item name='\\$ClassIndexItem'>.*?</item>")
            .matcher(dxl)
            .replaceFirst(
                java.util.regex.Matcher.quoteReplacement(
                    "<item name='$ClassIndexItem'><textlist/></item>"));
    String out = DesignDxl.withClassIndex(dxl);
    int page = out.indexOf("<text>WEB-INF/classes/xsp/Newxpage$NewxpagePage.class</text>");
    int host = out.indexOf("<text>WEB-INF/classes/xsp/Newxpage.class</text>");
    assertTrue(page >= 0 && host > page);
    assertFalse(out.contains("xsp/page/compiled"));
  }

  /** A class-data item whose class file names {@code internalName}, plus a package-path decoy. */
  private static String classItem(String itemName, String internalName) {
    byte[] name = internalName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.write(0x61);
    out.write(new byte[7], 0, 7);
    out.write(0xCA);
    out.write(0xFE);
    out.write(0xBA);
    out.write(0xBE);
    writeU2(out, 0);
    writeU2(out, 55);
    writeU2(out, 3);
    out.write(1);
    writeU2(out, name.length);
    out.write(name, 0, name.length);
    out.write(7);
    writeU2(out, 1);
    writeU2(out, 0x21);
    writeU2(out, 2);
    writeU2(out, 2);
    writeU2(out, 0);
    writeU2(out, 0);
    writeU2(out, 0);
    writeU2(out, 0);
    byte[] decoy = "xsp/page/compiled/AbstractCompiledPage".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    out.write(decoy, 0, decoy.length);
    String payload = java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    return "<item name='" + itemName + "'><rawitemdata type='1'>" + payload + "</rawitemdata></item>";
  }

  private static void writeU2(java.io.ByteArrayOutputStream out, int value) {
    out.write((value >> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  @Test
  void withoutReplicaIdKeepsTheDesignNote() {
    String dxl =
        "<form name='X' replicaid='C1258E90004E3F19'><noteinfo unid='U'/></noteinfo></form>";
    String out = DesignDxl.withoutReplicaId(dxl);
    assertFalse(out.toLowerCase().contains("replicaid"));
    assertTrue(out.contains("name='X'"));
    assertTrue(out.contains("<noteinfo unid='U'/>"));
  }

  @Test
  void forNewNoteRejectsBlank() {
    assertThrows(KeepExceptionWrongDataRequest.class, () -> DesignDxl.forNewNote("  "));
  }

  @Test
  void withNameSetsRootAndItems() throws Exception {
    String dxl =
        """
        <?xml version='1.0'?>
        <form name='Old' xmlns='http://www.lotus.com/dxl'>
        <item name='$TITLE'><text>Old</text></item>
        <item name='$$ScriptName'><text>Old</text></item>
        </form>
        """;
    String out = DesignDxl.withName(dxl, "NewForm", "form");
    assertEquals("NewForm", DesignDxl.requireDesignName(out, "form"));
    assertTrue(out.contains(">NewForm<"));
  }

  @Test
  void withNameKeepsXPageClassBytesAndFileName() throws Exception {
    String dxl =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/defaults/xpage.dxl"));
    String out = DesignDxl.withName(dxl, "SmokeXPage.xsp", "note");
    assertEquals("SmokeXPage.xsp", DesignDxl.requireDesignName(out, "note"));
    assertTrue(out.contains("<item name='$FileNames' sign='true'><text>newxpage.xsp</text>"));
    assertTrue(out.contains("<item name='$ClassData0'"));
    assertTrue(out.contains("<item name='$ClassData1'"));
    assertTrue(out.contains("WEB-INF/classes/xsp/Newxpage.class"));
  }

  @Test
  void requireDesignNameUsesTitlePipeSegment() throws Exception {
    String dxl =
        """
        <?xml version='1.0'?>
        <view xmlns='http://www.lotus.com/dxl'>
        <item name='$TITLE'><text>Hello|Alias</text></item>
        </view>
        """;
    assertEquals("Hello", DesignDxl.requireDesignName(dxl, "view"));
  }

  @Test
  void requireRootOnlyAcceptsSingletonRoots() throws Exception {
    String dxl =
        """
        <?xml version='1.0'?>
        <helpaboutdocument xmlns='http://www.lotus.com/dxl'/>
        """;
    DesignDxl.requireRootOnly(dxl, "helpaboutdocument");
  }

  @Test
  void requireDesignNameRejectsWrongRoot() {
    String dxl = "<?xml version='1.0'?><form name='X' xmlns='http://www.lotus.com/dxl'/>";
    assertThrows(KeepExceptionWrongDataRequest.class, () -> DesignDxl.requireDesignName(dxl, "view"));
  }
}
