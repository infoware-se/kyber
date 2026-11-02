package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hollow OpenNTF shells must be skipped from bulk export — never mistaken for real payload loss.
 * Positive cases must still export.
 */
class HollowDxlExportTest {

  @Test
  void hollowScriptLibraryWithoutCodeIsSkipped() {
    // Live export of sbtplayground gadgetSnippets.js — noteinfo only.
    String dxl =
        """
        <?xml version='1.0' encoding='utf-8'?>
        <!DOCTYPE scriptlibrary SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
        <scriptlibrary name='gadgetSnippets.js' xmlns='http://www.lotus.com/dxl'>
        <noteinfo noteid='43a' unid='5908508491381E4B85257C1A005CB512' sequence='3'>
        <created><datetime>20131105T115236,66-05</datetime></created>
        </noteinfo>
        <updatedby><name>CN=Padraic Edwards/OU=Ireland/O=IBM</name></updatedby>
        </scriptlibrary>
        """;
    assertFalse(DesignElementExport.hasRoundTripBody(dxl));
  }

  @Test
  void scriptLibraryWithJavascriptIsKept() {
    String dxl =
        """
        <scriptlibrary name='ok.js' xmlns='http://www.lotus.com/dxl'>
        <javascript><![CDATA[function hi(){ return 1; }]]></javascript>
        </scriptlibrary>
        """;
    assertTrue(DesignElementExport.hasRoundTripBody(dxl));
  }

  @Test
  void scriptLibraryWithLotusScriptIsKept() {
    String dxl =
        """
        <scriptlibrary name='Lib' xmlns='http://www.lotus.com/dxl'>
        <lotusscript><![CDATA[Option Public
        Sub Initialize
        End Sub]]></lotusscript>
        </scriptlibrary>
        """;
    assertTrue(DesignElementExport.hasRoundTripBody(dxl));
  }

  @Test
  void hollowImageWithOnlyFileMetaIsSkipped() {
    // Live export of XPagesExt profileNoPhoto-118px.png — $FileSize but no image bytes.
    String dxl =
        """
        <?xml version='1.0' encoding='utf-8'?>
        <imageresource name='profileNoPhoto-118px.png' xmlns='http://www.lotus.com/dxl'>
        <noteinfo noteid='2b2' unid='A9016303CE47B74680257BAB005507AC' sequence='6'></noteinfo>
        <updatedby><name>CN=Brian Gleeson/OU=Ireland/O=IBM</name></updatedby>
        <item name='$FileModDT' sign='true'><datetime>20110516T085604,59-04</datetime></item>
        <item name='$FileSize' sign='true'><number>6883</number></item>
        </imageresource>
        """;
    assertFalse(DesignElementExport.hasRoundTripBody(dxl));
  }

  @Test
  void imageWithGifPayloadIsKept() {
    String dxl =
        """
        <imageresource name='icon.gif' xmlns='http://www.lotus.com/dxl'>
        <gif>R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7</gif>
        </imageresource>
        """;
    assertTrue(DesignElementExport.hasRoundTripBody(dxl));
  }

  @Test
  void formsViewsAndOtherTypesUnaffected() {
    assertTrue(
        DesignElementExport.hasRoundTripBody(
            "<form name='F' xmlns='http://www.lotus.com/dxl'><body/></form>"));
    assertTrue(
        DesignElementExport.hasRoundTripBody(
            "<view name='V' xmlns='http://www.lotus.com/dxl'><column/></view>"));
  }

  @Test
  void appletResourceAndDataConnectionAreExportable() {
    // Bulk residual export previously dropped these roots (hasExportableDxl gap).
    assertTrue(
        DesignElementExport.hasRoundTripBody(
            """
            <?xml version='1.0' encoding='utf-8'?>
            <!DOCTYPE appletresource SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
            <appletresource name='SmokeApplet' xmlns='http://www.lotus.com/dxl'>
            <code event='action'><javaproject><java name='A.java'>class A{}</java></javaproject></code>
            </appletresource>
            """));
    assertTrue(
        DesignElementExport.hasRoundTripBody(
            """
            <?xml version='1.0' encoding='utf-8'?>
            <!DOCTYPE dataconnection SYSTEM 'xmlschemas/domino_14_5_1.dtd'>
            <dataconnection name='SmokeConnection' xmlns='http://www.lotus.com/dxl'
             class='rdbms' type='odbc2' datasource='DSN' objectname='T' objecttype='table'>
            </dataconnection>
            """));
  }
}
