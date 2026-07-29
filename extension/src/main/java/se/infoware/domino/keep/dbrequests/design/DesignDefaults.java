package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Resolves starter DXL: if configured NSF is present, use the first element of that type by
 * alphabetical name; else classpath {@code /defaults/{type}.dxl}.
 */
final class DesignDefaults {

  private static final String PROPERTIES = "/keep-design.properties";
  private static final String PROP_NSF = "keep.design.defaultNsf";
  private static final String FALLBACK_NSF = "keepdesign.nsf";

  /** Types with embedded starters in the JAR. */
  private static final String[] EMBEDDED_TYPES = {
    "about",
    "agent",
    "applicationproperties",
    "customcontrol",
    "databasescript",
    "fileresource",
    "folder",
    "form",
    "frameset",
    "image",
    "navigator",
    "outline",
    "page",
    "scriptlibrary",
    "sharedactions",
    "sharedcolumn",
    "sharedfield",
    "stylesheet",
    "subform",
    "theme",
    "using",
    "view",
    "webservice",
    "xpage",
    "xpagesresource",
    "xspproperties"
  };

  private DesignDefaults() {}

  static String defaultNsfPath() {
    try (InputStream in = DesignDefaults.class.getResourceAsStream(PROPERTIES)) {
      if (in == null) {
        return FALLBACK_NSF;
      }
      Properties p = new Properties();
      p.load(in);
      String path = p.getProperty(PROP_NSF, FALLBACK_NSF);
      return path == null || path.isBlank() ? FALLBACK_NSF : path.trim();
    } catch (Exception e) {
      return FALLBACK_NSF;
    }
  }

  static String normalizeType(String type) throws KeepExceptionWrongDataRequest {
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type path parameter is required");
    }
    String t = DesignBulkTypes.normalize(type);
    if (DesignBulkTypes.isKnown(t)) {
      return t;
    }
    // Accept plural API-ish forms: views -> view
    if (t.endsWith("s")) {
      String singular = t.substring(0, t.length() - 1);
      if (DesignBulkTypes.isKnown(singular)) {
        return singular;
      }
    }
    if (t.endsWith("ies")) {
      String singular = t.substring(0, t.length() - 3) + "y";
      if (DesignBulkTypes.isKnown(singular)) {
        return singular;
      }
    }
    throw new KeepExceptionWrongDataRequest("Unknown design type: " + type);
  }

  static Set<String> embeddedTypes() {
    Set<String> out = new LinkedHashSet<>();
    for (String t : EMBEDDED_TYPES) {
      if (classpathDxl(t).isPresent()) {
        out.add(t);
      }
    }
    return out;
  }

  static Optional<String> classpathDxl(String type) {
    String key = DesignBulkTypes.normalize(type);
    try (InputStream in = DesignDefaults.class.getResourceAsStream("/defaults/" + key + ".dxl")) {
      if (in == null) {
        return Optional.empty();
      }
      return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Resolve one starter. Prefers first NSF element of the type (A–Z by name); else JAR.
   */
  static JsonObject resolveOne(KeepJnxSession session, String type)
      throws KeepExceptionWrongDataRequest {
    String key = normalizeType(type);
    Optional<JsonObject> fromNsf = fromNsf(session, key);
    if (fromNsf.isPresent()) {
      return fromNsf.get();
    }
    Optional<String> embedded = classpathDxl(key);
    if (embedded.isEmpty()) {
      throw new KeepExceptionWrongDataRequest(
          "No default DXL for type '"
              + key
              + "' (not in "
              + defaultNsfPath()
              + " and not embedded)");
    }
    return new JsonObject()
        .put("type", key)
        .put("name", "Default")
        .put("dxl", embedded.get())
        .put("source", "embedded");
  }

  static JsonArray resolveAll(KeepJnxSession session) {
    Set<String> types = new LinkedHashSet<>(embeddedTypes());
    Optional<Database> nsf = tryOpenDefaultsDb(session);
    if (nsf.isPresent()) {
      try {
        JsonArray exported = DesignElementIO.exportAll(nsf.get());
        for (int i = 0; i < exported.size(); i++) {
          JsonObject el = exported.getJsonObject(i);
          String t = DesignBulkTypes.normalize(el.getString("type", ""));
          if (!t.isBlank()) {
            types.add(t);
          }
        }
      } catch (Exception ignored) {
        // fall through with embedded-only set
      }
    }

    JsonArray out = new JsonArray();
    for (String t : types) {
      try {
        out.add(resolveOne(session, t));
      } catch (KeepExceptionWrongDataRequest ignored) {
        // skip types with neither NSF nor embedded
      }
    }
    return out;
  }

  private static Optional<JsonObject> fromNsf(KeepJnxSession session, String type) {
    Optional<Database> dbOpt = tryOpenDefaultsDb(session);
    if (dbOpt.isEmpty()) {
      return Optional.empty();
    }
    try {
      JsonArray exported = DesignElementIO.exportAll(dbOpt.get());
      List<JsonObject> matches = new ArrayList<>();
      for (int i = 0; i < exported.size(); i++) {
        JsonObject el = exported.getJsonObject(i);
        String t = DesignBulkTypes.normalize(el.getString("type", ""));
        if (type.equals(t)) {
          matches.add(el);
        }
      }
      if (matches.isEmpty()) {
        return Optional.empty();
      }
      if (DesignBulkTypes.isSingleton(type) || matches.size() == 1) {
        return Optional.of(copyWithSource(matches.get(0), "nsf"));
      }
      matches.sort(
          Comparator.comparing(
              el -> el.getString("name", ""),
              String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())));
      return Optional.of(copyWithSource(matches.get(0), "nsf"));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static JsonObject copyWithSource(JsonObject el, String source) {
    JsonObject out = new JsonObject();
    out.put("type", DesignBulkTypes.normalize(el.getString("type", "")));
    String name = el.getString("name");
    if (name != null && !name.isBlank()) {
      out.put("name", name);
    }
    out.put("dxl", el.getString("dxl", ""));
    out.put("source", source);
    return out;
  }

  private static Optional<Database> tryOpenDefaultsDb(KeepJnxSession session) {
    String nsfPath = defaultNsfPath();
    try {
      DominoClient client = session.getClient();
      String server = "";
      String path = nsfPath;
      int bang = nsfPath.indexOf("!!");
      if (bang >= 0) {
        server = nsfPath.substring(0, bang);
        path = nsfPath.substring(bang + 2);
      }
      client.openDatabase(server, path);
      return Optional.of(session.getDatabaseDomino(nsfPath));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

}
