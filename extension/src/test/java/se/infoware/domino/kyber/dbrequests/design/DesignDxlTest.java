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
