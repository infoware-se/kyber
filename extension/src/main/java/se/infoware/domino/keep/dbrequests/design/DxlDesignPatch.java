package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.dxl.DxlImporter.DXLImportOption;
import com.hcl.domino.dxl.DxlImporter.DXLLogOption;
import com.hcl.domino.dxl.DxlImporter.XMLValidationOption;
import java.io.IOException;

/**
 * Shared DxlImporter settings for design element import - aligned with HCL UpdateDxl.
 */
final class DxlDesignPatch {

  private DxlDesignPatch() {}

  static String importDesign(DxlImporter importer, String dxl, Database db) throws IOException {
    configureCommon(importer);
    importer.setReplaceDbProperties(false);
    importer.importDxl(dxl, db);
    return importer.getResultLog();
  }

  /**
   * Import DXL that may carry database-level properties (e.g. root {@code <database>}).
   */
  static String importWithDbProperties(DxlImporter importer, String dxl, Database db)
      throws IOException {
    configureCommon(importer);
    importer.setReplaceDbProperties(true);
    importer.importDxl(dxl, db);
    return importer.getResultLog();
  }

  private static void configureCommon(DxlImporter importer) {
    importer.setExitOnFirstFatalError(false);
    importer.setDesignImportOption(DXLImportOption.REPLACE_ELSE_CREATE);
    importer.setDocumentsImportOption(DXLImportOption.IGNORE);
    importer.setACLImportOption(DXLImportOption.IGNORE);
    importer.setUnknownTokenLogOption(DXLLogOption.WARNING);
    importer.setReplicaRequiredForReplaceOrUpdate(false);
    importer.setInputValidationOption(XMLValidationOption.NEVER);
  }
}
