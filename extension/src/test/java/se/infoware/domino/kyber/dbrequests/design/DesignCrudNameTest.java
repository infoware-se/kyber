package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import org.junit.jupiter.api.Test;

class DesignCrudNameTest {

  private static final String FORM =
      """
      <?xml version='1.0'?>
      <form name='Old' replicaid='0123456789ABCDEF' xmlns='http://www.lotus.com/dxl'>
      <noteinfo unid='AABBCCDDEEFF00112233445566778899'>
      <created><datetime>20240101T120000,00Z</datetime></created>
      </noteinfo>
      <item name='$TITLE'><text>Old</text></item>
      </form>
      """;

  @Test
  void createQueryNameRewritesDxlAndDropsNoteIdentity() throws Exception {
    DesignCrud.NamedBody out =
        DesignCrud.applyNameParam(
            FORM,
            "Old",
            "NewForm",
            DxlDesignPatch.Mode.CREATE,
            (body, name) -> DesignDxl.withName(body, name, "form"));
    assertEquals("NewForm", out.name());
    assertEquals("NewForm", DesignDxl.requireDesignName(out.dxl(), "form"));
    assertFalse(out.dxl().toLowerCase().contains("<noteinfo"));
    assertFalse(out.dxl().toLowerCase().contains("replicaid="));
  }

  @Test
  void replaceRejectsADifferentName() {
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () ->
            DesignCrud.applyNameParam(
                FORM,
                "Old",
                "Other",
                DxlDesignPatch.Mode.REPLACE,
                (body, name) -> DesignDxl.withName(body, name, "form")));
  }

  @Test
  void blankQueryKeepsTheDxlName() throws Exception {
    DesignCrud.NamedBody out =
        DesignCrud.applyNameParam(
            FORM, "Old", "  ", DxlDesignPatch.Mode.CREATE, (body, name) -> body);
    assertEquals("Old", out.name());
    assertTrue(out.dxl().contains("name='Old'") || out.dxl().contains("name=\"Old\""));
    assertTrue(out.dxl().toLowerCase().contains("<noteinfo"));
  }
}
