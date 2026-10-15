package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.AboutDocument;
import com.hcl.domino.design.DatabaseScriptLibrary;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.UsingDocument;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.util.Locale;
import java.util.Optional;

/** Existence checks and DXL export for design CRUD create/update modes. */
final class DesignElementLookup {

  static final String DBICON_NAME = "$DBIcon";

  private DesignElementLookup() {}

  static boolean exists(Database db, String type, String name) throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if (DesignBulkTypes.isSingleton(t) || "dbicon".equals(t)) {
      return existsSingleton(db, t);
    }
    if (name == null || name.isBlank()) {
      return false;
    }
    String n = name.trim();
    return switch (t) {
      case "form" -> db.getDesign().getForm(n).isPresent();
      case "page" -> db.getDesign().getPage(n).isPresent();
      case "outline" -> db.getDesign().getOutline(n).isPresent();
      case "frameset" -> {
        try {
          yield db.getDesign().getFrameset(n).isPresent();
        } catch (RuntimeException e) {
          yield false;
        }
      }
      case "view" -> db.getDesign().getView(n).isPresent();
      case "folder" -> db.getDesign().getFolder(n).isPresent();
      case "subform" -> db.getDesign().getSubform(n).isPresent();
      case "sharedfield" -> db.getDesign().getSharedField(n).isPresent();
      case "sharedcolumn" -> db.getDesign().getSharedColumn(n).isPresent();
      case "sharedactions" -> SharedActionsNotes.find(db, n).isPresent();
      case "agent" -> db.getDesign().getAgent(n).isPresent();
      case "scriptlibrary" -> db.getDesign().getScriptLibrary(n).isPresent();
      case "image" -> db.getDesign().getImageResource(n).isPresent();
      case "dbicon" -> db.getDesign().getImageResource(DBICON_NAME).isPresent();
      case "fileresource" -> db.getDesign().getFileResource(n).isPresent();
      case "stylesheet" -> db.getDesign().getStyleSheet(n).isPresent();
      case "theme" -> db.getDesign().getTheme(n).isPresent();
      case "jar", "applet", "javasource", "dataconnection", "webcontent" ->
          BulkOnlyNotes.find(db, t, n).isPresent();
      case "navigator" -> db.getDesign().getNavigator(n).isPresent();
      case "xpage" -> db.getDesign().getXPage(n).isPresent();
      case "customcontrol" -> CustomControls.find(db, n).isPresent();
      case "webservice" -> WebServices.find(db, WebServices.Kind.PROVIDER, n).isPresent();
      case "webserviceconsumer" -> WebServices.find(db, WebServices.Kind.CONSUMER, n).isPresent();
      case "wiringproperties" -> db.getDesign().getWiringPropertiesElement(n).isPresent();
      case "compositeapplication" -> db.getDesign().getCompositeApplication(n).isPresent();
      case "compositecomponent" -> db.getDesign().getCompositeComponent(n).isPresent();
      default -> throw new KeepExceptionWrongDataRequest("Unsupported type for exists: " + t);
    };
  }

  static boolean existsSingleton(Database db, String type) throws Exception {
    String t = DesignBulkTypes.normalize(type);
    return switch (t) {
      case "about" -> db.getDesign().getAboutDocument().isPresent();
      case "using" -> db.getDesign().getUsingDocument().isPresent();
      case "databasescript" -> db.getDesign().getDatabaseScriptLibrary().isPresent();
      case "xspproperties" -> BulkOnlyNotes.find(db, "xspproperties", "WEB-INF/xsp.properties").isPresent();
      case "dbicon" -> db.getDesign().getImageResource(DBICON_NAME).isPresent();
      case "applicationproperties" -> {
        var props = db.getDesign().getDatabaseProperties();
        yield props != null && props.getDocument() != null;
      }
      case "acl" -> true;
      default -> throw new KeepExceptionWrongDataRequest("Unsupported singleton: " + t);
    };
  }

  static Optional<String> exportDxl(Database db, String type, String name) throws Exception {
    String t = DesignBulkTypes.normalize(type);
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    if ("dbicon".equals(t) || (name != null && DBICON_NAME.equalsIgnoreCase(name.trim()))) {
      Optional<? extends DesignElement> opt = db.getDesign().getImageResource(DBICON_NAME);
      return exportDoc(exporter, opt.map(DesignElement::getDocument));
    }
    if (DesignBulkTypes.isSingleton(t)) {
      return exportSingleton(db, t, exporter);
    }
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String n = name.trim();
    return switch (t) {
      case "form" -> exportDoc(exporter, db.getDesign().getForm(n).map(DesignElement::getDocument));
      case "page" -> exportDoc(exporter, db.getDesign().getPage(n).map(DesignElement::getDocument));
      case "outline" ->
          exportDoc(exporter, db.getDesign().getOutline(n).map(DesignElement::getDocument));
      case "frameset" -> {
        try {
          yield exportDoc(
              exporter, db.getDesign().getFrameset(n).map(DesignElement::getDocument));
        } catch (RuntimeException e) {
          yield Optional.empty();
        }
      }
      case "view" -> exportDoc(exporter, db.getDesign().getView(n).map(DesignElement::getDocument));
      case "folder" ->
          exportDoc(exporter, db.getDesign().getFolder(n).map(DesignElement::getDocument));
      case "subform" ->
          exportDoc(exporter, db.getDesign().getSubform(n).map(DesignElement::getDocument));
      case "sharedfield" ->
          exportDoc(exporter, db.getDesign().getSharedField(n).map(DesignElement::getDocument));
      case "sharedcolumn" ->
          exportDoc(exporter, db.getDesign().getSharedColumn(n).map(DesignElement::getDocument));
      case "sharedactions" ->
          exportDoc(
              exporter, SharedActionsNotes.find(db, n).map(DesignElement::getDocument));
      case "agent" ->
          exportDoc(exporter, db.getDesign().getAgent(n).map(DesignElement::getDocument));
      case "scriptlibrary" ->
          exportDoc(exporter, db.getDesign().getScriptLibrary(n).map(DesignElement::getDocument));
      case "image" ->
          exportDoc(exporter, db.getDesign().getImageResource(n).map(DesignElement::getDocument));
      case "fileresource" ->
          exportDoc(exporter, db.getDesign().getFileResource(n).map(DesignElement::getDocument));
      case "stylesheet" ->
          exportDoc(exporter, db.getDesign().getStyleSheet(n).map(DesignElement::getDocument));
      case "theme" ->
          exportDoc(exporter, db.getDesign().getTheme(n).map(DesignElement::getDocument));
      case "navigator" ->
          exportDoc(exporter, db.getDesign().getNavigator(n).map(DesignElement::getDocument));
      case "xpage" ->
          exportDoc(exporter, db.getDesign().getXPage(n).map(DesignElement::getDocument));
      case "wiringproperties" ->
          exportDoc(
              exporter,
              db.getDesign().getWiringPropertiesElement(n).map(DesignElement::getDocument));
      case "compositeapplication" ->
          exportDoc(
              exporter,
              db.getDesign().getCompositeApplication(n).map(DesignElement::getDocument));
      case "compositecomponent" ->
          exportDoc(
              exporter,
              db.getDesign().getCompositeComponent(n).map(DesignElement::getDocument));
      case "customcontrol" ->
          exportDoc(exporter, CustomControls.find(db, n).map(CustomControls.Entry::document));
      case "webservice" ->
          exportDoc(
              exporter,
              WebServices.find(db, WebServices.Kind.PROVIDER, n).map(WebServices.Entry::document));
      case "webserviceconsumer" ->
          exportDoc(
              exporter,
              WebServices.find(db, WebServices.Kind.CONSUMER, n).map(WebServices.Entry::document));
      case "jar", "applet", "javasource", "dataconnection", "webcontent" ->
          exportDoc(exporter, BulkOnlyNotes.find(db, t, n).map(BulkOnlyNotes.Entry::document));
      default -> Optional.empty();
    };
  }

  private static Optional<String> exportDoc(
      DxlExporter exporter, Optional<? extends com.hcl.domino.data.Document> doc) throws Exception {
    if (doc.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(exporter.exportDocument(doc.get()));
  }

  private static Optional<String> exportSingleton(Database db, String type, DxlExporter exporter)
      throws Exception {
    return switch (type) {
      case "about" ->
          exportDoc(
              exporter, db.getDesign().getAboutDocument().map(AboutDocument::getDocument));
      case "using" ->
          exportDoc(
              exporter, db.getDesign().getUsingDocument().map(UsingDocument::getDocument));
      case "databasescript" ->
          exportDoc(
              exporter,
              db.getDesign().getDatabaseScriptLibrary().map(DatabaseScriptLibrary::getDocument));
      case "xspproperties" ->
          exportDoc(
              exporter,
              BulkOnlyNotes.find(db, "xspproperties", "WEB-INF/xsp.properties")
                  .map(BulkOnlyNotes.Entry::document));
      case "dbicon" ->
          exportDoc(
              exporter, db.getDesign().getImageResource(DBICON_NAME).map(DesignElement::getDocument));
      default -> Optional.empty();
    };
  }

  static boolean isDbIconName(String name) {
    return name != null && DBICON_NAME.equalsIgnoreCase(name.trim());
  }

  static String normalizeImageNameCheck(String name) throws KeepExceptionWrongDataRequest {
    if (isDbIconName(name)) {
      throw new KeepExceptionWrongDataRequest(
          "$DBIcon is a singleton — use /dbicon instead of /images");
    }
    return name;
  }

  static String typeLabel(String type) {
    String t = DesignBulkTypes.normalize(type);
    if (t.isEmpty()) {
      return type;
    }
    return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
  }
}
