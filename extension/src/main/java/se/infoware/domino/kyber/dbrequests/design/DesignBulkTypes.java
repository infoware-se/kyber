package se.infoware.domino.kyber.dbrequests.design;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bulk design DXL type keys (API / repo) and import ordering.
 *
 * <p>API types use short names ({@code about}, {@code image}); Create* handlers may emit DXL-root
 * style names ({@code helpaboutdocument}, {@code imageresource}).
 */
final class DesignBulkTypes {

  /** Relative repo directory for named types; empty means root file {@code <type>.dxl}.
   * Scripts now prefer DXL {@code <!DOCTYPE>} name as folder ({@code form/}, {@code
   * imageresource/}); this map remains a fallback / documentation for API types. */
  static final Map<String, String> REPO_DIR;

  private static final Map<String, Integer> IMPORT_ORDER;

  static {
    Map<String, String> dirs = new LinkedHashMap<>();
    dirs.put("acl", "acl");
    dirs.put("applicationproperties", "");
    dirs.put("about", "");
    dirs.put("using", "");
    dirs.put("sharedactions", "sharedactions");
    dirs.put("databasescript", "");
    dirs.put("dbicon", "");
    dirs.put("image", "images");
    dirs.put("fileresource", "fileresources");
    dirs.put("stylesheet", "stylesheets");
    dirs.put("theme", "themes");
    dirs.put("scriptlibrary", "scriptlibraries");
    dirs.put("sharedfield", "sharedfields");
    dirs.put("sharedcolumn", "sharedcolumns");
    dirs.put("subform", "subforms");
    dirs.put("form", "forms");
    dirs.put("page", "pages");
    dirs.put("outline", "outlines");
    dirs.put("navigator", "navigators");
    dirs.put("frameset", "framesets");
    dirs.put("view", "views");
    dirs.put("folder", "folders");
    dirs.put("agent", "agents");
    dirs.put("webservice", "webservices");
    dirs.put("webserviceconsumer", "webserviceconsumers");
    dirs.put("dataconnection", "dataconnections");
    dirs.put("jar", "jars");
    dirs.put("applet", "applets");
    dirs.put("javasource", "javasources");
    dirs.put("xspproperties", "");
    dirs.put("xpagesresource", "xpagesresources");
    dirs.put("designnote", "designnotes");
    dirs.put("customcontrol", "customcontrols");
    dirs.put("xpage", "xpages");
    dirs.put("wiringproperties", "wiringproperties");
    dirs.put("compositeapplication", "compositeapplications");
    dirs.put("compositecomponent", "compositecomponents");
    REPO_DIR = Collections.unmodifiableMap(dirs);

    Map<String, Integer> order = new LinkedHashMap<>();
    int i = 0;
    for (String t :
        new String[] {
          "applicationproperties",
          "about",
          "using",
          "sharedactions",
          "databasescript",
          "dbicon",
          "image",
          "fileresource",
          "jar",
          "applet",
          "javasource",
          "stylesheet",
          "theme",
          "xspproperties",
          "xpagesresource",
          "designnote",
          "scriptlibrary",
          "sharedfield",
          "sharedcolumn",
          "subform",
          "form",
          "page",
          "outline",
          "navigator",
          "frameset",
          "view",
          "folder",
          "agent",
          "webservice",
          "webserviceconsumer",
          "dataconnection",
          "customcontrol",
          "xpage",
          "wiringproperties",
          "compositeapplication",
          "compositecomponent",
          // ACL last: apply after design so the payload ACL is the final state when present
          "acl"
        }) {
      order.put(t, i++);
    }
    IMPORT_ORDER = Collections.unmodifiableMap(order);
  }

  private static final Set<String> SINGLETONS =
      Set.of(
          "acl",
          "applicationproperties",
          "about",
          "using",
          "databasescript",
          "xspproperties",
          "dbicon");

  private DesignBulkTypes() {}

  static String normalize(String type) {
    if (type == null || type.isBlank()) {
      return "";
    }
    String t = type.trim().toLowerCase(Locale.ROOT);
    return switch (t) {
      case "helpaboutdocument" -> "about";
      case "helpusingdocument" -> "using";
      case "imageresource" -> "image";
      case "stylesheetresource" -> "stylesheet";
      default -> t;
    };
  }

  static boolean isKnown(String type) {
    return IMPORT_ORDER.containsKey(normalize(type));
  }

  static boolean isSingleton(String type) {
    return SINGLETONS.contains(normalize(type));
  }

  static int importOrder(String type) {
    return IMPORT_ORDER.getOrDefault(normalize(type), Integer.MAX_VALUE);
  }

  static String repoDir(String type) {
    return REPO_DIR.getOrDefault(normalize(type), normalize(type) + "s");
  }
}
