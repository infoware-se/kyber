package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
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
 * Shared create / update orchestration for design CRUD endpoints.
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

  private static void writeNamed(
      DbRequestParameters<JsonObject> request,
      String type,
      DxlDesignPatch.Mode mode,
      boolean allowEmptyDefault,
      String... dxlRoots)
      throws Exception {
    ensureEmptyDefaultsNsf(request);
    String t = DesignBulkTypes.normalize(type);
    String dxl = request.incoming.getString("dxl", "");
    String nameParam = request.incoming.getString("name");
    String source = "dxl";
    String name;
    boolean codeWrite = codeWrite(request.incoming, dxl);
    if (codeWrite) {
      requireCodeType(t);
    }

    if (codeWrite && mode == DxlDesignPatch.Mode.REPLACE) {
      if (nameParam == null || nameParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest("name is required");
      }
      String wanted = nameParam.trim();
      name = wanted;
      Database db = request.db();
      notFoundIfMissing(db, t, wanted);
      String current =
          DesignElementLookup.exportDxl(db, t, wanted)
              .orElseThrow(() -> new KeepExceptionNotFound(t + " not found: " + wanted));
      dxl = DesignCode.apply(current, request.incoming.getString("code", ""), t);
      source = "code";
    } else if (dxl == null || dxl.isBlank()) {
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
      dxl = DesignDxl.forNewNote(rewriteName(t, starter.getString("dxl", ""), name, dxlRoots));
      if (codeWrite) {
        dxl = DesignCode.apply(dxl, request.incoming.getString("code", ""), t);
        source = "code";
      } else {
        source = "default";
      }
    } else {
      String fromDxl =
          "sharedactions".equals(t)
              ? DesignDxl.requireSharedActionsName(dxl)
              : DesignDxl.requireDesignName(dxl, dxlRoots);
      NamedBody named =
          applyNameParam(
              dxl,
              fromDxl,
              nameParam,
              mode,
              (body, wanted) -> rewriteName(t, body, wanted, dxlRoots));
      dxl = named.dxl();
      name = named.name();
      if ("image".equals(t)) {
        DesignElementLookup.normalizeImageNameCheck(name);
      }
    }

    Database db = request.db();
    JsonObject xpagesProject = null;
    if ("xpage".equals(t) && mode == DxlDesignPatch.Mode.CREATE) {
      xpagesProject = XPagesProject.ensure(request.session(), db);
    }
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
        DesignElementImport.importOne(importer, db, t, dxl, request.session(), importMode);
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
    if (xpagesProject != null) {
      out.put("xpagesProject", xpagesProject);
    }
    if (imported.containsKey("signWarning")) {
      out.put("signWarning", imported.getString("signWarning"));
    }
    DesignPayload.enrich(out, exported);
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

  private static void writeKinded(
      DbRequestParameters<JsonObject> request,
      String type,
      KindAdapter kinds,
      DxlDesignPatch.Mode mode,
      boolean allowEmptyDefault,
      String... dxlRoots)
      throws Exception {
    ensureEmptyDefaultsNsf(request);
    String t = DesignBulkTypes.normalize(type);
    String dxl = request.incoming.getString("dxl", "");
    String kindParam = request.incoming.getString("kind");
    String nameParam = request.incoming.getString("name");
    String kind;
    String name;
    String source;
    boolean codeWrite = codeWrite(request.incoming, dxl);
    if (codeWrite) {
      requireCodeType(t);
    }

    if (codeWrite && mode == DxlDesignPatch.Mode.REPLACE) {
      if (nameParam == null || nameParam.isBlank()) {
        throw new KeepExceptionWrongDataRequest("name is required");
      }
      String wanted = nameParam.trim();
      name = wanted;
      Database db = request.db();
      notFoundIfMissing(db, t, wanted);
      String current =
          DesignElementLookup.exportDxl(db, t, wanted)
              .orElseThrow(() -> new KeepExceptionNotFound(t + " not found: " + wanted));
      kind =
          kindParam != null && !kindParam.isBlank()
              ? kinds.normalize(kindParam)
              : kinds.detectOrDefault(current);
      dxl = DesignCode.apply(current, request.incoming.getString("code", ""), t);
      source = "code";
    } else if (dxl == null || dxl.isBlank()) {
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
      dxl = DesignDxl.forNewNote(kinds.withName(starter.getString("dxl", ""), name));
      if (codeWrite) {
        dxl = DesignCode.apply(dxl, request.incoming.getString("code", ""), t);
        source = "code";
      } else {
        source = "default";
      }
    } else {
      String fromDxl = DesignDxl.requireDesignName(dxl, dxlRoots);
      kind =
          kindParam != null && !kindParam.isBlank()
              ? kinds.normalize(kindParam)
              : kinds.detectOrDefault(dxl);
      source = "dxl";
      NamedBody named = applyNameParam(dxl, fromDxl, nameParam, mode, kinds::withName);
      dxl = named.dxl();
      name = named.name();
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
        DesignElementImport.importOne(importer, db, t, dxl, request.session(), importMode);
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
    DesignPayload.enrich(out, exported);
    request.emit(out);
  }

  /** Singleton create (POST). Empty body → default. */
  static void createSingleton(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeSingleton(request, type, DxlDesignPatch.Mode.CREATE, true, dxlRoots);
  }

  /** Singleton update (PUT). DXL required. */
  static void updateSingleton(
      DbRequestParameters<JsonObject> request, String type, String... dxlRoots) throws Exception {
    writeSingleton(request, type, DxlDesignPatch.Mode.REPLACE, false, dxlRoots);
  }

  private static void writeSingleton(
      DbRequestParameters<JsonObject> request,
      String type,
      DxlDesignPatch.Mode mode,
      boolean allowEmptyDefault,
      String... dxlRoots)
      throws Exception {
    ensureEmptyDefaultsNsf(request);
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
        DesignElementImport.importOne(importer, db, importType, dxl, request.session(), importMode);
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
    DesignPayload.enrich(out, exported);
    request.emit(out);
  }

  private static boolean codeWrite(JsonObject incoming, String dxl) {
    return incoming.containsKey("code") && (dxl == null || dxl.isBlank());
  }

  private static void requireCodeType(String type) throws KeepExceptionWrongDataRequest {
    if (!DesignCode.accepts(type)) {
      throw new KeepExceptionWrongDataRequest(
          "code is accepted for agent, scriptlibrary, javasource, webcontent, fileresource, stylesheet, and xpagesresource");
    }
  }

  private static String rewriteName(String type, String dxl, String name, String... dxlRoots)
      throws KeepExceptionWrongDataRequest {
    if ("sharedactions".equals(type)) {
      return DesignDxl.withName(dxl, name, "sharedactions");
    }
    if ("webcontent".equals(type)) {
      return WebContent.withName(dxl, name);
    }
    if ("xpagesresource".equals(type)) {
      return XPagesResource.withName(dxl, name);
    }
    return DesignDxl.withName(dxl, name, dxlRoots);
  }

  private static JsonObject resolveSingletonDefault(KeepJnxSession session, String type)
      throws KeepExceptionWrongDataRequest {
    return DesignDefaults.resolveOne(session, type);
  }

  /**
   * On create, a {@code name} query that differs from the DXL renames the note and strips note
   * identity so the source UNID is not replaced. On replace, a different name is rejected.
   */
  static NamedBody applyNameParam(
      String dxl,
      String nameFromDxl,
      String nameParam,
      DxlDesignPatch.Mode mode,
      NameRewriter rewriter)
      throws KeepExceptionWrongDataRequest {
    if (nameParam == null || nameParam.isBlank()) {
      return new NamedBody(dxl, nameFromDxl);
    }
    String wanted = nameParam.trim();
    if (wanted.equalsIgnoreCase(nameFromDxl)) {
      return new NamedBody(dxl, wanted);
    }
    if (mode != DxlDesignPatch.Mode.CREATE) {
      throw new KeepExceptionWrongDataRequest(
          "path name '" + wanted + "' does not match DXL name '" + nameFromDxl + "'");
    }
    return new NamedBody(DesignDxl.forNewNote(rewriter.rewrite(dxl, wanted)), wanted);
  }

  @FunctionalInterface
  interface NameRewriter {
    String rewrite(String dxl, String name) throws KeepExceptionWrongDataRequest;
  }

  record NamedBody(String dxl, String name) {}

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

  private static void ensureEmptyDefaultsNsf(DbRequestParameters<JsonObject> request)
      throws Exception {
    ImportDesignDxl.ensureEmptyDefaultsNsf(request.session(), DominoClient.Encryption.None);
  }
}
