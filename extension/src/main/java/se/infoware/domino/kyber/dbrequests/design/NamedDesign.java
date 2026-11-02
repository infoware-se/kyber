package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;

/**
 * Typed CRUD when the Domino name must not sit in the URL path (parentheses, etc.).
 *
 * <p>Routes: {@code GET|PUT|DELETE /named/{type}?nsfPath=&name=}.
 */
final class NamedDesign {

  private NamedDesign() {}

  static void get(DbRequestParameters<JsonObject> request) throws Exception {
    String type = RequestParams.requireType(request.incoming);
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    String dxl =
        DesignElementLookup.exportDxl(db, type, name)
            .orElseThrow(
                () ->
                    new KeepExceptionWrongDataRequest(
                        DesignElementLookup.typeLabel(type) + " not found: " + name));
    request.emit(DesignPayload.note(name, dxl));
  }

  static void update(DbRequestParameters<JsonObject> request) throws Exception {
    String type = RequestParams.requireType(request.incoming);
    // Ensure name is present before DesignCrud (query param, not path).
    RequestParams.requireName(request.incoming);
    switch (type) {
      case "agent" -> DesignCrud.updateKinded(request, type, DesignCrud.AGENT, "agent");
      case "scriptlibrary" ->
          DesignCrud.updateKinded(request, type, DesignCrud.SCRIPT_LIBRARY, "scriptlibrary");
      case "javasource" ->
          DesignCrud.updateKinded(
              request, type, DesignCrud.JAVA_SOURCE, "note", "fileresource", "appletresource");
      case "form" -> DesignCrud.updateNamed(request, type, "form");
      case "page" -> DesignCrud.updateNamed(request, type, "page");
      case "outline" -> DesignCrud.updateNamed(request, type, "outline");
      case "frameset" -> DesignCrud.updateNamed(request, type, "frameset");
      case "view" -> DesignCrud.updateNamed(request, type, "view");
      case "folder" -> DesignCrud.updateNamed(request, type, "folder");
      case "subform" -> DesignCrud.updateNamed(request, type, "subform");
      case "sharedfield" -> DesignCrud.updateNamed(request, type, "sharedfield");
      case "sharedcolumn" -> DesignCrud.updateNamed(request, type, "sharedcolumn");
      case "sharedactions" -> DesignCrud.updateNamed(request, type, "sharedactions");
      case "image" -> DesignCrud.updateNamed(request, type, "imageresource");
      case "fileresource" -> DesignCrud.updateNamed(request, type, "fileresource");
      case "stylesheet" -> DesignCrud.updateNamed(request, type, "stylesheetresource");
      case "theme" -> DesignCrud.updateNamed(request, type, "theme", "note");
      case "navigator" -> DesignCrud.updateNamed(request, type, "navigator", "note");
      case "xpage" -> DesignCrud.updateNamed(request, type, "note", "fileresource", "xpage");
      case "customcontrol" -> DesignCrud.updateNamed(request, type, "note", "fileresource");
      case "webservice" ->
          DesignCrud.updateNamed(request, type, "note", "fileresource", "webservice", "agent");
      case "webserviceconsumer" ->
          DesignCrud.updateNamed(
              request, type, "note", "fileresource", "scriptlibrary", "webservice");
      case "dataconnection" ->
          DesignCrud.updateNamed(request, type, "dataconnection", "note", "fileresource");
      case "jar" ->
          DesignCrud.updateNamed(request, type, "note", "fileresource", "appletresource");
      case "applet" ->
          DesignCrud.updateNamed(request, type, "note", "fileresource", "appletresource");
      case "xpagesresource" -> DesignCrud.updateNamed(request, type, "note");
      case "webcontent" -> DesignCrud.updateNamed(request, type, "note");
      case "wiringproperties" -> DesignCrud.updateNamed(request, type, "note", "fileresource");
      case "compositeapplication" ->
          DesignCrud.updateNamed(request, type, "note", "fileresource");
      case "compositecomponent" ->
          DesignCrud.updateNamed(request, type, "note", "fileresource");
      default ->
          throw new KeepExceptionWrongDataRequest("Unsupported named type for update: " + type);
    }
  }

  static void delete(DbRequestParameters<JsonObject> request) throws Exception {
    String type = RequestParams.requireType(request.incoming);
    String name = RequestParams.requireName(request.incoming);
    Database db = request.db();
    DesignAccess.requireDeleteDocuments(request.session(), db);
    DesignElementLookup.deleteNamed(db, type, name);
    request.emit(new JsonObject().put("success", true).put("type", type).put("name", name));
  }
}
