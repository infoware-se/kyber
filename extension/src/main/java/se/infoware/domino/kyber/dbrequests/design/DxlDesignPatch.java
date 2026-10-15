package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.dxl.DxlImporter.DXLImportOption;
import com.hcl.domino.dxl.DxlImporter.DXLLogOption;
import com.hcl.domino.dxl.DxlImporter.XMLValidationOption;
import java.io.IOException;

/**
 * Shared DxlImporter settings for design element import - aligned with HCL UpdateDxl.
 *
 * <p>Display defaults (e.g. view bgcolor) live in starter DXL under {@code /defaults} and optional
 * {@code kyber.nsf} — not injected here.
 */
final class DxlDesignPatch {

  enum Mode {
    /** Create only ({@link DXLImportOption#CREATE}). */
    CREATE,
    /** Replace existing only ({@link DXLImportOption#REPLACE_ELSE_IGNORE}). */
    REPLACE,
    /** Create or replace ({@link DXLImportOption#REPLACE_ELSE_CREATE}) — legacy upsert. */
    UPSERT
  }

  private DxlDesignPatch() {}

  static String importDesign(DxlImporter importer, String dxl, Database db) throws IOException {
    return importDesign(importer, dxl, db, Mode.UPSERT);
  }

  static String importDesign(DxlImporter importer, String dxl, Database db, Mode mode)
      throws IOException {
    configureCommon(importer, designOption(mode));
    importer.setReplaceDbProperties(false);
    importer.importDxl(dxl, db);
    return importer.getResultLog();
  }

  /**
   * Import DXL that may carry database-level properties (e.g. root {@code <database>}).
   */
  static String importWithDbProperties(DxlImporter importer, String dxl, Database db)
      throws IOException {
    return importWithDbProperties(importer, dxl, db, Mode.UPSERT);
  }

  static String importWithDbProperties(
      DxlImporter importer, String dxl, Database db, Mode mode) throws IOException {
    configureCommon(importer, designOption(mode));
    importer.setReplaceDbProperties(true);
    importer.importDxl(dxl, db);
    return importer.getResultLog();
  }

  /** Updates database launch settings. Design notes, documents, and the ACL stay as they are. */
  static String importLaunchSettings(DxlImporter importer, String dxl, Database db) throws IOException {
    importer.setExitOnFirstFatalError(false);
    importer.setDesignImportOption(DXLImportOption.IGNORE);
    importer.setDocumentsImportOption(DXLImportOption.IGNORE);
    importer.setACLImportOption(DXLImportOption.IGNORE);
    importer.setReplaceDbProperties(true);
    importer.setReplicaRequiredForReplaceOrUpdate(false);
    importer.setInputValidationOption(XMLValidationOption.NEVER);
    importer.importDxl(dxl, db);
    return importer.getResultLog();
  }

  /**
   * Import root {@code <acl>} DXL. Design notes stay ignored; only ACL is replaced/created.
   */
  static String importAcl(DxlImporter importer, String dxl, Database db) throws IOException {
    importer.setExitOnFirstFatalError(false);
    importer.setDesignImportOption(DXLImportOption.IGNORE);
    importer.setDocumentsImportOption(DXLImportOption.IGNORE);
    importer.setACLImportOption(DXLImportOption.REPLACE_ELSE_CREATE);
    importer.setReplaceDbProperties(false);
    importer.setUnknownTokenLogOption(DXLLogOption.WARNING);
    importer.setReplicaRequiredForReplaceOrUpdate(false);
    importer.setInputValidationOption(XMLValidationOption.NEVER);
    importer.importDxl(dxl, db);
    return importer.getResultLog();
  }

  private static DXLImportOption designOption(Mode mode) {
    if (mode == null) {
      return DXLImportOption.REPLACE_ELSE_CREATE;
    }
    return switch (mode) {
      case CREATE -> DXLImportOption.CREATE;
      case REPLACE -> DXLImportOption.REPLACE_ELSE_IGNORE;
      case UPSERT -> DXLImportOption.REPLACE_ELSE_CREATE;
    };
  }

  private static void configureCommon(DxlImporter importer, DXLImportOption designOption) {
    importer.setExitOnFirstFatalError(false);
    importer.setDesignImportOption(designOption);
    importer.setDocumentsImportOption(DXLImportOption.IGNORE);
    importer.setACLImportOption(DXLImportOption.IGNORE);
    importer.setUnknownTokenLogOption(DXLLogOption.WARNING);
    importer.setReplicaRequiredForReplaceOrUpdate(false);
    importer.setInputValidationOption(XMLValidationOption.NEVER);
  }
}
