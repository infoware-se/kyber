package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TableDxlFixTest {

  @Test
  void prepareIncomingWrapsOrphanParInTablerow() {
    String dxl =
        """
        <subform name='reportOptions'>
        <tablerow>
        <par def='6'><border outsidewidth='5px'/></par>
        <par def='6'><border outsidewidth='5px'/></par>
        <tablecell borderwidth='1px'><par def='5'>x</par></tablecell>
        </tablerow>
        </subform>
        """;
    String fixed = TableDxlFix.prepareIncomingDxl(dxl);
    assertTrue(fixed.contains("<tablecell><par def='6'>"));
    assertTrue(fixed.contains("</par></tablecell>"));
    assertTrue(fixed.contains("<tablecell borderwidth='1px'>"));
    // Idempotent
    assertEquals(fixed, TableDxlFix.prepareIncomingDxl(fixed));
  }

  @Test
  void prepareIncomingLeavesCleanTablerowAlone() {
    String dxl =
        "<tablerow><tablecell><par>a</par></tablecell><tablecell><par>b</par></tablecell></tablerow>";
    assertEquals(dxl, TableDxlFix.prepareIncomingDxl(dxl));
  }

  @Test
  void prepareIncomingStillWrapsWhenTablecellPresent() {
    // Regression: nested-table skip must not treat <tablecell> as <table>.
    String dxl =
        "<tablerow><par>x</par><tablecell><par>y</par></tablecell></tablerow>";
    String fixed = TableDxlFix.prepareIncomingDxl(dxl);
    assertTrue(fixed.contains("<tablecell><par>x</par></tablecell>"));
  }

  @Test
  void prepareIncomingNoopWithoutTablerow() {
    String dxl = "<form name='x'><par>hi</par></form>";
    assertEquals(dxl, TableDxlFix.prepareIncomingDxl(dxl));
    assertEquals(null, TableDxlFix.prepareIncomingDxl(null));
  }

  @Test
  void prepareIncomingWrapsOrphanCompositedataInTablerow() {
    // OpenNTF sbtplayground form GlobalOptions — importer 6922 on bare <compositedata>.
    String dxl =
        """
        <form name='GlobalOptions'>
        <tablerow>
        <compositedata type='embed'/>
        <tablecell><par>x</par></tablecell>
        </tablerow>
        </form>
        """;
    String fixed = TableDxlFix.prepareIncomingDxl(dxl);
    assertTrue(fixed.contains("<tablecell><compositedata type='embed'/></tablecell>"));
    assertTrue(fixed.contains("<tablecell><par>x</par></tablecell>"));
    assertEquals(fixed, TableDxlFix.prepareIncomingDxl(fixed));
  }

  @Test
  void prepareIncomingExpandsEmptySelfClosingTablerow() {
    // Mail SignatureOption* rowspan placeholders — importer rejects <tablerow/>.
    String dxl =
        """
        <table>
        <tablerow><tablecell><par>a</par></tablecell></tablerow>
        <tablerow/>
        <tablerow />
        <tablerow><tablecell><par>b</par></tablecell></tablerow>
        </table>
        """;
    String fixed = TableDxlFix.prepareIncomingDxl(dxl);
    assertTrue(fixed.contains("<tablerow></tablerow>"));
    assertTrue(!fixed.matches("(?s).*<tablerow\\s*/>.*"));
    assertEquals(fixed, TableDxlFix.prepareIncomingDxl(fixed));
  }
}
