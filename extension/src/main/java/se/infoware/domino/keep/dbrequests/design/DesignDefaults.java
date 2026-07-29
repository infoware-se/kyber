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
 *
 * <p>Kinded types:
 *
 * <ul>
 *   <li>{@code scriptlibrary} — kind lotusscript|javascript|ssjs|java
 *   <li>{@code agent} — kind lotusscript|formula|simpleaction|java
 * </ul>
 */
final class DesignDefaults {

  private static final String PROPERTIES = "/keep-design.properties";
  private static final String PROP_NSF = "keep.design.defaultNsf";
  private static final String FALLBACK_NSF = "keepdesign.nsf";

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
    return classpathDxl(type, null);
  }

  static Optional<String> classpathDxl(String type, String kind) {
    String key = DesignBulkTypes.normalize(type);
    String resource = "/defaults/" + key + ".dxl";
    if (kind != null && !kind.isBlank() && isKindedType(key)) {
      try {
        String suffix = embeddedSuffix(key, kind);
        if (!suffix.isEmpty()) {
          resource = "/defaults/" + key + suffix + ".dxl";
        }
      } catch (KeepExceptionWrongDataRequest e) {
        return Optional.empty();
      }
    }
    try (InputStream in = DesignDefaults.class.getResourceAsStream(resource)) {
      if (in == null) {
        return Optional.empty();
      }
      return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  static JsonObject resolveOne(KeepJnxSession session, String type)
      throws KeepExceptionWrongDataRequest {
    return resolveOne(session, type, null);
  }

  /**
   * Resolve one starter. For kinded types, {@code kind} selects the language/action variant.
   * Prefers first NSF element of matching type/kind; else JAR.
   */
  static JsonObject resolveOne(KeepJnxSession session, String type, String kind)
      throws KeepExceptionWrongDataRequest {
    String key = normalizeType(type);
    String resolvedKind = isKindedType(key) ? normalizeKind(key, kind) : null;

    Optional<JsonObject> fromNsf = fromNsf(session, key, resolvedKind);
    if (fromNsf.isPresent()) {
      return fromNsf.get();
    }
    Optional<String> embedded = classpathDxl(key, resolvedKind);
    if (embedded.isEmpty()) {
      throw new KeepExceptionWrongDataRequest(
          "No default DXL for type '"
              + key
              + (resolvedKind != null ? "' kind '" + resolvedKind : "")
              + "' (not in "
              + defaultNsfPath()
              + " and not embedded)");
    }
    JsonObject out =
        new JsonObject()
            .put("type", key)
            .put("name", "Default")
            .put("dxl", embedded.get())
            .put("source", "embedded");
    if (resolvedKind != null) {
      out.put("kind", resolvedKind);
      out.put("name", defaultNameFor(key, resolvedKind));
    }
    return out;
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
      String[] kinds = kindsFor(t);
      if (kinds != null) {
        for (String kind : kinds) {
          try {
            out.add(resolveOne(session, t, kind));
          } catch (KeepExceptionWrongDataRequest ignored) {
            // skip missing kind
          }
        }
        continue;
      }
      try {
        out.add(resolveOne(session, t));
      } catch (KeepExceptionWrongDataRequest ignored) {
        // skip types with neither NSF nor embedded
      }
    }
    return out;
  }

  private static boolean isKindedType(String type) {
    return "scriptlibrary".equals(type) || "agent".equals(type);
  }

  private static String[] kindsFor(String type) {
    if ("scriptlibrary".equals(type)) {
      return ScriptLibraryKinds.ALL;
    }
    if ("agent".equals(type)) {
      return AgentKinds.ALL;
    }
    return null;
  }

  private static String normalizeKind(String type, String kind)
      throws KeepExceptionWrongDataRequest {
    if ("scriptlibrary".equals(type)) {
      return ScriptLibraryKinds.normalize(kind);
    }
    if ("agent".equals(type)) {
      return AgentKinds.normalize(kind);
    }
    throw new KeepExceptionWrongDataRequest("type does not support kind: " + type);
  }

  private static String embeddedSuffix(String type, String kind)
      throws KeepExceptionWrongDataRequest {
    if ("scriptlibrary".equals(type)) {
      return ScriptLibraryKinds.embeddedSuffix(kind);
    }
    if ("agent".equals(type)) {
      return AgentKinds.embeddedSuffix(kind);
    }
    return "";
  }

  private static String detectKind(String type, String dxl) {
    if ("scriptlibrary".equals(type)) {
      return ScriptLibraryKinds.detect(dxl);
    }
    if ("agent".equals(type)) {
      return AgentKinds.detect(dxl);
    }
    return null;
  }

  private static String detectOrDefault(String type, String dxl) {
    if ("scriptlibrary".equals(type)) {
      return ScriptLibraryKinds.detectOrDefault(dxl);
    }
    if ("agent".equals(type)) {
      return AgentKinds.detectOrDefault(dxl);
    }
    return null;
  }

  private static String defaultNameFor(String type, String kind)
      throws KeepExceptionWrongDataRequest {
    if ("scriptlibrary".equals(type)) {
      return switch (ScriptLibraryKinds.normalize(kind)) {
        case ScriptLibraryKinds.JAVASCRIPT -> "DefaultClientJs";
        case ScriptLibraryKinds.SSJS -> "DefaultSsjs";
        case ScriptLibraryKinds.JAVA -> "DefaultJava";
        default -> "DefaultLotusScript";
      };
    }
    if ("agent".equals(type)) {
      return AgentKinds.defaultName(kind);
    }
    return "Default";
  }

  private static Optional<JsonObject> fromNsf(
      KeepJnxSession session, String type, String resolvedKind) {
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
        if (!type.equals(t)) {
          continue;
        }
        if (resolvedKind != null) {
          String detected = detectKind(type, el.getString("dxl", ""));
          if (detected == null || !resolvedKind.equals(detected)) {
            continue;
          }
        }
        matches.add(el);
      }
      if (matches.isEmpty()) {
        return Optional.empty();
      }
      if (DesignBulkTypes.isSingleton(type) || matches.size() == 1) {
        return Optional.of(copyWithSource(matches.get(0), "nsf", resolvedKind));
      }
      matches.sort(
          Comparator.comparing(
              el -> el.getString("name", ""),
              String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())));
      return Optional.of(copyWithSource(matches.get(0), "nsf", resolvedKind));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static JsonObject copyWithSource(JsonObject el, String source, String resolvedKind) {
    JsonObject out = new JsonObject();
    String type = DesignBulkTypes.normalize(el.getString("type", ""));
    out.put("type", type);
    String name = el.getString("name");
    if (name != null && !name.isBlank()) {
      out.put("name", name);
    }
    String dxl = el.getString("dxl", "");
    out.put("dxl", dxl);
    out.put("source", source);
    if (isKindedType(type)) {
      out.put(
          "kind",
          resolvedKind != null && !resolvedKind.isBlank()
              ? resolvedKind
              : detectOrDefault(type, dxl));
    }
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
