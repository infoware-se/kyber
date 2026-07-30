package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.ErrorClassification;
import com.hcl.domino.keep.exceptions.KeepException;
import com.hcl.domino.keep.exceptions.KeepExceptionNotFound;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;

/**
 * Shared create / update / upsert orchestration for design CRUD endpoints.
 *
 * <p>Create: 409 if exists. Update: 404 if missing. Both return exported {@code dxl} when possible.
 */
final class DesignCrud {

  private DesignCrud() {}

  static void conflictIfExists(Database db, String type, String name) throws Exception {
    if (DesignElementLookup.exists(db, type, name)) {
      String label = DesignElementLookup.typeLabel(type);
      String msg =
          name == null || name.isBlank()
              ? label + " already exists"
              : label + " already exists: " + name;
      throw KeepException.status(msg, ErrorClassification.CONFLICT);
    }
  }

  static void notFoundIfMissing(Database db, String type, String name) throws Exception {
    if (!DesignElementLookup.exists(db, type, name)) {
      String label = DesignElementLookup.typeLabel(type);
      String msg =
          name == null || name.isBlank() ? label + " not found" : label + " not found: " + name;
      throw new KeepExceptionNotFound(msg);
    }
  }

  /**
   * Named-type create (POST). Empty body → default starter + {@code name} query param.
   *
   * @param dxlRoots DXL root local names accepted for body validation / withName
   */
  static void createNamed(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeNamed(request, type, DxlDesignPatch.Mode.CREATE, true, dxlRoots);
  }

  /** Named-type update (PUT /{name}). DXL body required; path name must match. */
  static void updateNamed(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeNamed(request, type, DxlDesignPatch.Mode.REPLACE, false, dxlRoots);
  }

  /** Named-type upsert (deprecated collection PUT). */
  static void upsertNamed(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeNamed(request, type, DxlDesignPatch.Mode.UPSERT, false, dxlRoots);
  }

  private static void writeNamed(
      DbRequestParameters<JsonObject> request,
      String type,
      DxlDesignPatch.Mode mode,
      boolean allowEmptyDefault,
      String... dxlRoots)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    String dxl = request.incoming.getString("dxl", "");
    String nameParam = request.incoming.getString("name");
    String source = "dxl";
    String name;

    if (dxl == null || dxl.isBlank()) {
      if (!allowEmptyDefault || mode != DxlDesignPatch.Mode.CREATE) {
        throw new KeepExceptionWrongDataRequest("dxl body is required");
      }
      if (nameParam == null || nameParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest("name is required when dxl body is empty");
      }
      name = nameParam.trim();
      if ("image".equals(t)) {
        DesignElementLookup.normalizeImageNameCheck(name);
      }
      JsonObject starter = DesignDefaults.resolveOne(request.session(), t);
      dxl = DesignDxl.withName(starter.getString("dxl", ""), name, dxlRoots);
      source = "default";
    } else {
      name =
          "sharedactions".equals(t)
              ? DesignDxl.requireSharedActionsName(dxl)
              : DesignDxl.requireDesignName(dxl, dxlRoots);
      if ("image".equals(t)) {
        DesignElementLookup.normalizeImageNameCheck(name);
      }
      if (nameParam != null
          && !nameParam.isBlank()
          && !nameParam.trim().equalsIgnoreCase(name)
          && mode == DxlDesignPatch.Mode.REPLACE) {
        throw new KeepExceptionWrongDataRequest(
            "path name '" + nameParam.trim() + "' does not match DXL name '" + name + "'");
      }
      if (mode == DxlDesignPatch.Mode.REPLACE
          && nameParam != null
          && !nameParam.isBlank()) {
        name = nameParam.trim();
      }
    }

    Database db = request.db();
    if (mode == DxlDesignPatch.Mode.CREATE) {
      conflictIfExists(db, t, name);
    } else if (mode == DxlDesignPatch.Mode.REPLACE) {
      String lookupName =
          nameParam != null && !nameParam.isBlank() ? nameParam.trim() : name;
      notFoundIfMissing(db, t, lookupName);
      name = lookupName;
    }

    // Domino DXLImportOption.CREATE is unreliable for some note types; after an explicit
    // exists-check, UPSERT is equivalent to create-only.
    DxlDesignPatch.Mode importMode =
        mode == DxlDesignPatch.Mode.CREATE ? DxlDesignPatch.Mode.UPSERT : mode;

    DxlImporter importer = request.session().getDxlImporter();
    JsonObject imported =
        DesignElementIO.importOne(importer, db, t, dxl, request.session(), importMode);
    String exported =
        DesignElementLookup.exportDxl(db, t, name).orElse(dxl);

    JsonObject out =
        new JsonObject()
            .put("success", true)
            .put("type", t)
            .put("name", name)
            .put("source", source)
            .put("dxl", exported)
            .put("log", imported.getString("log", ""));
    if (imported.containsKey("signWarning")) {
      out.put("signWarning", imported.getString("signWarning"));
    }
    request.emit(out);
  }

  /** Kinded create (agent / scriptlibrary / javasource). */
  static void createKinded(
      DbRequestParameters<JsonObject> request,
      String type,
      KindAdapter kinds,
      String... dxlRoots)
      throws Exception {
    writeKinded(request, type, kinds, DxlDesignPatch.Mode.CREATE, true, dxlRoots);
  }

  static void updateKinded(
      DbRequestParameters<JsonObject> request,
      String type,
      KindAdapter kinds,
      String... dxlRoots)
      throws Exception {
    writeKinded(request, type, kinds, DxlDesignPatch.Mode.REPLACE, false, dxlRoots);
  }

  static void upsertKinded(
      DbRequestParameters<JsonObject> request,
      String type,
      KindAdapter kinds,
      String... dxlRoots)
      throws Exception {
    writeKinded(request, type, kinds, DxlDesignPatch.Mode.UPSERT, true, dxlRoots);
  }

  private static void writeKinded(
      DbRequestParameters<JsonObject> request,
      String type,
      KindAdapter kinds,
      DxlDesignPatch.Mode mode,
      boolean allowEmptyDefault,
      String... dxlRoots)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    String dxl = request.incoming.getString("dxl", "");
    String kindParam = request.incoming.getString("kind");
    String nameParam = request.incoming.getString("name");
    String kind;
    String name;
    String source;

    if (dxl == null || dxl.isBlank()) {
      if (!allowEmptyDefault) {
        throw new KeepExceptionWrongDataRequest("dxl body is required");
      }
      if (kindParam == null || kindParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest(
            "kind is required when dxl body is empty");
      }
      if (nameParam == null || nameParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest("name is required when dxl body is empty");
      }
      kind = kinds.normalize(kindParam);
      name = nameParam.trim();
      JsonObject starter = DesignDefaults.resolveOne(request.session(), t, kind);
      dxl = kinds.withName(starter.getString("dxl", ""), name);
      source = "default";
    } else {
      name = DesignDxl.requireDesignName(dxl, dxlRoots);
      kind =
          kindParam != null && !kindParam.isBlank()
              ? kinds.normalize(kindParam)
              : kinds.detectOrDefault(dxl);
      source = "dxl";
      if (mode == DxlDesignPatch.Mode.REPLACE
          && nameParam != null
          && !nameParam.isBlank()) {
        if (!nameParam.trim().equalsIgnoreCase(name)) {
          throw new KeepExceptionWrongDataRequest(
              "path name '" + nameParam.trim() + "' does not match DXL name '" + name + "'");
        }
        name = nameParam.trim();
      }
    }

    Database db = request.db();
    if (mode == DxlDesignPatch.Mode.CREATE) {
      conflictIfExists(db, t, name);
    } else if (mode == DxlDesignPatch.Mode.REPLACE) {
      notFoundIfMissing(db, t, name);
    }

    DxlDesignPatch.Mode importMode =
        mode == DxlDesignPatch.Mode.CREATE ? DxlDesignPatch.Mode.UPSERT : mode;

    DxlImporter importer = request.session().getDxlImporter();
    JsonObject imported =
        DesignElementIO.importOne(importer, db, t, dxl, request.session(), importMode);
    String exported = DesignElementLookup.exportDxl(db, t, name).orElse(dxl);

    JsonObject out =
        new JsonObject()
            .put("success", true)
            .put("type", t)
            .put("name", name)
            .put("kind", kind)
            .put("source", source)
            .put("dxl", exported)
            .put("log", imported.getString("log", ""));
    if (imported.containsKey("signWarning")) {
      out.put("signWarning", imported.getString("signWarning"));
    }
    request.emit(out);
  }

  /** Singleton create (POST). Empty body → default. */
  static void createSingleton(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeSingleton(request, type, DxlDesignPatch.Mode.CREATE, true, dxlRoots);
  }

  /** Singleton update (PUT). DXL required unless empty→default disallowed. */
  static void updateSingleton(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeSingleton(request, type, DxlDesignPatch.Mode.REPLACE, false, dxlRoots);
  }

  /** Singleton upsert (deprecated PUT). */
  static void upsertSingleton(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeSingleton(request, type, DxlDesignPatch.Mode.UPSERT, false, dxlRoots);
  }

  private static void writeSingleton(
      DbRequestParameters<JsonObject> request,
      String type,
      DxlDesignPatch.Mode mode,
      boolean allowEmptyDefault,
      String... dxlRoots)
      throws Exception {
    String t = DesignBulkTypes.normalize(type);
    if ("dbicon".equalsIgnoreCase(type)) {
      t = "dbicon";
    }
    String dxl = request.incoming.getString("dxl", "");
    String source = "dxl";
    String importType = "dbicon".equals(t) ? "image" : t;

    if (dxl == null || dxl.isBlank()) {
      if (!allowEmptyDefault || mode != DxlDesignPatch.Mode.CREATE) {
        throw new KeepExceptionWrongDataRequest("dxl body is required");
      }
      String defaultType = "dbicon".equals(t) ? "dbicon" : t;
      JsonObject starter = resolveSingletonDefault(request.session(), defaultType);
      dxl = starter.getString("dxl", "");
      source = "default";
      if ("dbicon".equals(t)) {
        dxl = DesignDxl.withName(dxl, DesignElementLookup.DBICON_NAME, "imageresource");
      } else if (dxlRoots != null && dxlRoots.length > 0) {
        DesignDxl.requireRootOnly(dxl, dxlRoots[0]);
      }
    } else if (dxlRoots != null && dxlRoots.length > 0) {
      if ("dbicon".equals(t)) {
        String name = DesignDxl.requireRootName(dxl, "imageresource");
        if (!DesignElementLookup.isDbIconName(name)) {
          throw new KeepExceptionWrongDataRequest(
              "dbicon DXL must use name=\"" + DesignElementLookup.DBICON_NAME + "\"");
        }
      } else {
        DesignDxl.requireRootOnlyReturningLocalName(dxl, dxlRoots);
      }
    }

    Database db = request.db();
    String existsName = "dbicon".equals(t) ? DesignElementLookup.DBICON_NAME : null;
    if (mode == DxlDesignPatch.Mode.CREATE) {
      conflictIfExists(db, t, existsName);
    } else if (mode == DxlDesignPatch.Mode.REPLACE) {
      notFoundIfMissing(db, t, existsName);
    }

    DxlDesignPatch.Mode importMode =
        mode == DxlDesignPatch.Mode.CREATE ? DxlDesignPatch.Mode.UPSERT : mode;

    DxlImporter importer = request.session().getDxlImporter();
    JsonObject imported =
        DesignElementIO.importOne(importer, db, importType, dxl, request.session(), importMode);
    String exported = DesignElementLookup.exportDxl(db, t, existsName).orElse(dxl);

    JsonObject out =
        new JsonObject()
            .put("success", true)
            .put("type", t)
            .put("source", source)
            .put("dxl", exported)
            .put("log", imported.getString("log", ""));
    if ("dbicon".equals(t)) {
      out.put("name", DesignElementLookup.DBICON_NAME);
    }
    if (imported.containsKey("signWarning")) {
      out.put("signWarning", imported.getString("signWarning"));
    }
    request.emit(out);
  }

  private static JsonObject resolveSingletonDefault(KeepJnxSession session, String type)
      throws KeepExceptionWrongDataRequest {
    if ("dbicon".equals(type)) {
      try {
        return DesignDefaults.resolveOne(session, "dbicon");
      } catch (KeepExceptionWrongDataRequest e) {
        // Fall back to blank NSF baseline packaged under defaults/blank/
        return loadClasspathDefault("/defaults/blank/image-DBIcon.dxl", "dbicon");
      }
    }
    return DesignDefaults.resolveOne(session, type);
  }

  private static JsonObject loadClasspathDefault(String resource, String type)
      throws KeepExceptionWrongDataRequest {
    try (var in = DesignCrud.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new KeepExceptionWrongDataRequest("No default DXL for type: " + type);
      }
      String dxl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      return new JsonObject()
          .put("type", type)
          .put("source", "classpath")
          .put("dxl", dxl);
    } catch (KeepExceptionWrongDataRequest e) {
      throw e;
    } catch (Exception e) {
      throw new KeepExceptionWrongDataRequest("Cannot load default for " + type + ": " + e);
    }
  }

  interface KindAdapter {
    String normalize(String kind) throws KeepExceptionWrongDataRequest;

    String detectOrDefault(String dxl);

    String withName(String dxl, String name) throws KeepExceptionWrongDataRequest;
  }

  static final KindAdapter SCRIPT_LIBRARY =
      new KindAdapter() {
        @Override
        public String normalize(String kind) throws KeepExceptionWrongDataRequest {
          return ScriptLibraryKinds.normalize(kind);
        }

        @Override
        public String detectOrDefault(String dxl) {
          return ScriptLibraryKinds.detectOrDefault(dxl);
        }

        @Override
        public String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
          return ScriptLibraryKinds.withName(dxl, name);
        }
      };

  static final KindAdapter AGENT =
      new KindAdapter() {
        @Override
        public String normalize(String kind) throws KeepExceptionWrongDataRequest {
          return AgentKinds.normalize(kind);
        }

        @Override
        public String detectOrDefault(String dxl) {
          return AgentKinds.detectOrDefault(dxl);
        }

        @Override
        public String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
          return AgentKinds.withName(dxl, name);
        }
      };

  static final KindAdapter JAVA_SOURCE =
      new KindAdapter() {
        @Override
        public String normalize(String kind) throws KeepExceptionWrongDataRequest {
          return JavaSourceKinds.normalize(kind);
        }

        @Override
        public String detectOrDefault(String dxl) {
          return JavaSourceKinds.detectOrDefault(dxl);
        }

        @Override
        public String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
          return JavaSourceKinds.withName(dxl, name);
        }
      };
}
