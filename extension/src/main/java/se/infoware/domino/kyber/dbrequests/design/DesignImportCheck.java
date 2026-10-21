package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;

/**
 * The checks {@code PUT /import} makes before Domino writes a note. {@code POST /validate} runs
 * these and does not import. A note Domino itself refuses is still reported by the import.
 */
final class DesignImportCheck {

  private DesignImportCheck() {}

  static void check(JsonObject element) throws KeepExceptionWrongDataRequest {
    if (element == null) {
      throw new KeepExceptionWrongDataRequest("element must be an object");
    }
    String rawType = element.getString("type", "");
    String type = DesignBulkTypes.normalize(rawType);
    if (!DesignBulkTypes.isKnown(type)) {
      throw new KeepExceptionWrongDataRequest("Unknown design element type: " + rawType);
    }
    if (element.getBoolean("delete", false)) {
      if ("acl".equals(type)) {
        throw new KeepExceptionWrongDataRequest(
            "Cannot delete the ACL with the design import delete flag");
      }
      String name = element.getString("name");
      if (name == null || name.isBlank()) {
        throw new KeepExceptionWrongDataRequest("name is required to delete type " + type);
      }
      return;
    }
    String dxl = element.getString("dxl", "");
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl is required for type " + type);
    }
    switch (type) {
      case "acl" -> DesignDxl.requireProtectedAclEntries(dxl);
      case "applicationproperties" ->
          DesignDxl.requireRootOnlyReturningLocalName(dxl, "note", "database");
      case "about" -> DesignDxl.requireRootOnly(dxl, "helpaboutdocument");
      case "using" -> DesignDxl.requireRootOnly(dxl, "helpusingdocument");
      case "sharedactions" -> DesignDxl.requireSharedActionsName(dxl);
      case "databasescript" -> DesignDxl.requireRootOnly(dxl, "databasescript");
      case "dbicon" -> requireDbIcon(dxl);
      case "image" -> DesignDxl.requireRootName(dxl, "imageresource");
      case "fileresource" -> DesignDxl.requireRootName(dxl, "fileresource");
      case "stylesheet" -> DesignDxl.requireRootName(dxl, "stylesheetresource");
      case "theme" -> DesignDxl.requireDesignName(dxl, "theme", "note");
      case "scriptlibrary" -> DesignDxl.requireRootName(dxl, "scriptlibrary");
      case "sharedfield" -> DesignDxl.requireRootName(dxl, "sharedfield");
      case "sharedcolumn" -> DesignDxl.requireRootName(dxl, "sharedcolumn");
      case "subform" -> DesignDxl.requireRootName(dxl, "subform");
      case "form" -> DesignDxl.requireRootName(dxl, "form");
      case "page" -> DesignDxl.requireRootName(dxl, "page");
      case "outline" -> DesignDxl.requireRootName(dxl, "outline");
      case "frameset" -> DesignDxl.requireRootName(dxl, "frameset");
      case "view" -> DesignDxl.requireRootName(dxl, "view");
      case "folder" -> DesignDxl.requireRootName(dxl, "folder");
      case "agent" -> DesignDxl.requireRootName(dxl, "agent");
      case "customcontrol", "wiringproperties", "compositeapplication", "compositecomponent" ->
          DesignDxl.requireDesignName(dxl, "note", "fileresource");
      case "webservice" ->
          DesignDxl.requireDesignName(dxl, "note", "fileresource", "webservice", "agent");
      case "webserviceconsumer" ->
          DesignDxl.requireDesignName(dxl, "note", "fileresource", "scriptlibrary", "webservice");
      case "xpage" -> DesignDxl.requireDesignName(dxl, "note", "fileresource", "xpage");
      case "navigator" -> DesignDxl.requireDesignName(dxl, "navigator", "note");
      case "dataconnection" ->
          DesignDxl.requireDesignName(dxl, "dataconnection", "note", "fileresource");
      case "jar", "applet", "javasource", "webcontent", "xspproperties", "xpagesresource", "designnote" -> {
        String name =
            DesignDxl.requireDesignName(
                dxl, "note", "fileresource", "navigator", "webservice", "agent", "appletresource");
        if ("webcontent".equals(type)) {
          WebContent.requireOutsideWebInf(name);
        }
      }
      default -> throw new KeepExceptionWrongDataRequest("Unsupported type: " + type);
    }
  }

  private static void requireDbIcon(String dxl) throws KeepExceptionWrongDataRequest {
    String name = DesignDxl.requireRootName(dxl, "imageresource");
    if (!DesignElementLookup.isDbIconName(name)) {
      throw new KeepExceptionWrongDataRequest(
          "dbicon DXL must use name=\"" + DesignElementLookup.DBICON_NAME + "\"");
    }
  }
}
