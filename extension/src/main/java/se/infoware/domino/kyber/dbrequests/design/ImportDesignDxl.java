package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoException;
import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bulk import design DXL - PUT /designdxl?nsfPath=&dbTitle=
 *
 * <p>HTTP handler remaps query {@code nsfPath} (target) to {@code targetNsfPath} and opens
 * {@code KeepConfig.nsf} as the KEEP connection DB so a missing NSF can be created first. The
 * {@link MinimumAcl} on this class is that bootstrap database only. Writes are authorized against
 * the target NSF: design changes need Designer, and replacing the ACL needs Manager.
 *
 * <p>This is the only endpoint that may create the target NSF. Per-type CRUD requires an existing
 * database. The API never deletes databases. The first write to any NSF also creates {@code
 * kyber.nsf} when that file is missing. That database is left empty of design templates. It gets a
 * title, the Kyber ACL, and the same usage text in About and Using, so templates can be added
 * later in Domino Designer.
 *
 * <p>Optional query {@code dbTitle} sets the database title when the NSF is created (ignored if
 * the NSF already exists). Do not use {@code title} — KEEP may populate that from the bootstrap DB.
 *
 * <p>On create of {@code kyber.nsf}, the ACL matches that database: {@code -Default-} and
 * {@code Anonymous} have no access, {@code OtherDomainServers} has no access, and
 * {@code LocalKeepAdmins}, {@code LocalDomainAdmins}, and {@code LocalDomainServers} are Manager.
 * Any other new NSF gets the {@code kyber_discussion145.nsf} ACL, with the creating user and the
 * server the file is created on in place of the names in that database. Those lists are written
 * only when the NSF is created. When the payload includes {@code type=acl}, that ACL DXL fully
 * replaces the database ACL and is left as imported. A created NSF receives the embedded Kyber
 * database icon unless the payload already contains one. On {@code kyber.nsf} that icon is the
 * database tile only; it is not a design template.
 *
 * <p>Elements may set {@code "delete": true} with {@code name} and/or {@code unid} to remove a
 * design note (bulk-only cleanup) instead of importing DXL.
 */
@MinimumAcl(AclLevel.READER)
public class ImportDesignDxl extends AsyncDominoJNXJson {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    String target =
        firstNonBlank(
            request.incoming.getString("targetNsfPath"),
            request.incoming.getString("nsfPath"));
    if (target == null || target.isBlank()) {
      throw new KeepExceptionWrongDataRequest("nsfPath (target) is required");
    }
    target = target.trim();

    // Prefer dbTitle — KEEP request params often already contain "title" (bootstrap NSF title).
    String title =
        firstNonBlank(request.incoming.getString("dbTitle"), request.incoming.getString("databaseTitle"));
    if (title != null) {
      title = title.trim();
      if (title.isEmpty()) {
        title = null;
      }
    }

    JsonObject payload = request.incoming.getJsonObject("body", request.incoming);
    JsonArray elements = payload.getJsonArray("elements");
    if (elements == null) {
      elements = request.incoming.getJsonArray("elements");
    }
    if (elements == null) {
      throw new KeepExceptionWrongDataRequest("elements array is required");
    }

    boolean hasAcl = containsType(elements, "acl");

    KeepJnxSession session = request.session();
    DominoClient.Encryption encryption = encryptionOf(request.incoming.getString("encryption"));
    if (!isDefaultNsfPath(target)) {
      ensureEmptyDefaultsNsf(session, encryption);
    }
    boolean created = openOrCreate(session, target, encryption);

    Database db = openTargetForWrite(session, target, requiredWriteLevel(elements));
    if (created && title == null) {
      title = defaultTitleForNewNsf(target);
    }
    if (created && title != null) {
      db.setTitle(title);
    }
    DxlImporter importer = session.getDxlImporter();

    List<JsonObject> ordered = DesignElementImport.sortForImport(elements);
    JsonArray results = new JsonArray();
    int ok = 0;
    int failed = 0;
    String aclError = null;
    for (JsonObject el : ordered) {
      String type = el.getString("type", "");
      String dxl = el.getString("dxl", "");
      boolean isAcl = "acl".equalsIgnoreCase(DesignBulkTypes.normalize(type));
      boolean delete = el.getBoolean("delete", false);
      try {
        JsonObject one;
        if (delete) {
          if (isAcl) {
            throw new KeepExceptionWrongDataRequest("Cannot delete ACL via designdxl delete flag");
          }
          DesignAccess.requireDeleteDocuments(session, db);
          one =
              DesignElementImport.deleteOne(
                  db, type, el.getString("name"), el.getString("unid"));
        } else {
          one = DesignElementImport.importOne(importer, db, type, dxl, session);
        }
        results.add(one);
        ok++;
      } catch (Exception e) {
        failed++;
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (isAcl) {
          aclError = message;
        }
        JsonObject row =
            new JsonObject()
                .put("success", false)
                .put("type", type)
                .put("message", message);
        if (el.getString("name") != null) {
          row.put("name", el.getString("name"));
        }
        results.add(row);
        // ACL in payload must be applied; stop so we do not leave a half-imported ACL state unclear
        if (isAcl) {
          break;
        }
      }
    }

    if (hasAcl && aclError != null) {
      throw new KeepExceptionWrongDataRequest("ACL import failed: " + aclError);
    }
    if (hasAcl && !resultsContainSuccessfulAcl(results)) {
      throw new KeepExceptionWrongDataRequest(
          "ACL was present in the import payload but was not applied successfully");
    }

    if (shouldApplyDefaultDbIcon(created, target, elements)) {
      try {
        String iconDxl =
            DesignDefaults.productDbIconDxl()
                .orElseThrow(
                    () -> new IllegalStateException("Embedded Kyber database icon is missing"));
        JsonObject one = DesignElementImport.importOne(importer, db, "image", iconDxl, session);
        one.put("type", "dbicon");
        results.add(one);
        ok++;
      } catch (Exception e) {
        failed++;
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        results.add(
            new JsonObject()
                .put("success", false)
                .put("type", "dbicon")
                .put("name", DesignElementLookup.DBICON_NAME)
                .put("message", message));
      }
    }

    JsonObject compile = DesignLotusScriptCompile.compileAll(db);

    JsonObject out =
        new JsonObject()
            .put("success", failed == 0)
            .put("created", created)
            .put("nsfPath", target)
            .put("imported", ok)
            .put("failed", failed)
            .put("aclApplied", hasAcl)
            .put("results", results)
            .put("lotusscript", compile);
    if (created && title != null) {
      out.put("dbTitle", title);
    }
    request.emit(out);
  }

  /**
   * A database this endpoint creates gets the embedded icon unless the payload already supplies
   * {@code $DBIcon}.
   */
  static boolean shouldApplyDefaultDbIcon(boolean created, String nsfPath, JsonArray elements) {
    return created && !payloadSuppliesDbIcon(elements);
  }

  private static boolean payloadSuppliesDbIcon(JsonArray elements) {
    if (containsType(elements, "dbicon")) {
      return true;
    }
    for (int i = 0; i < elements.size(); i++) {
      Object o = elements.getValue(i);
      if (!(o instanceof JsonObject jo)) {
        continue;
      }
      if (!"image".equalsIgnoreCase(jo.getString("type", "").trim())) {
        continue;
      }
      String dxl = jo.getString("dxl", "");
      if (dxl.contains("name='$DBIcon'") || dxl.contains("name=\"$DBIcon\"")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Access the caller must already have on an existing target. ACL replacement needs Manager.
   * Any other element needs Designer. An empty payload does not write.
   */
  static AclLevel requiredWriteLevel(JsonArray elements) {
    boolean design = false;
    boolean acl = false;
    if (elements != null) {
      for (int i = 0; i < elements.size(); i++) {
        Object value = elements.getValue(i);
        if (!(value instanceof JsonObject element)) {
          continue;
        }
        String type = DesignBulkTypes.normalize(element.getString("type", ""));
        if (type.isBlank()) {
          continue;
        }
        if ("acl".equals(type)) {
          acl = true;
        } else {
          design = true;
        }
      }
    }
    if (acl) {
      return AclLevel.MANAGER;
    }
    if (design) {
      return AclLevel.DESIGNER;
    }
    return null;
  }

  /**
   * Opens the target and rejects the call when the authenticated user lacks {@code required} on
   * that NSF. {@code null} only opens the database.
   */
  private static Database openTargetForWrite(
      KeepJnxSession session, String target, AclLevel required) throws Exception {
    if (required == null) {
      return session.getDatabaseDomino(target);
    }
    return session.getDatabaseDomino(target, required, Set.of(), Set.of());
  }

  private static boolean containsType(JsonArray elements, String type) {
    String want = DesignBulkTypes.normalize(type);
    for (int i = 0; i < elements.size(); i++) {
      Object o = elements.getValue(i);
      if (o instanceof JsonObject jo) {
        if (want.equals(DesignBulkTypes.normalize(jo.getString("type", "")))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean resultsContainSuccessfulAcl(JsonArray results) {
    for (int i = 0; i < results.size(); i++) {
      Object o = results.getValue(i);
      if (!(o instanceof JsonObject jo)) {
        continue;
      }
      if (!"acl".equals(DesignBulkTypes.normalize(jo.getString("type", "")))) {
        continue;
      }
      return jo.getBoolean("success", false);
    }
    return false;
  }

  /**
   * Title applied when a bulk create omits {@code dbTitle}. Other filenames keep Domino's own
   * default until the caller sets one.
   */
  static String defaultTitleForNewNsf(String nsfPath) {
    if (isDefaultNsfPath(nsfPath)) {
      return "Kyber Design Elements";
    }
    if (fileName(nsfPath).equalsIgnoreCase("kybertest.nsf")) {
      return "Kyber Smoke Test";
    }
    return null;
  }

  /** True when the path is the defaults database ({@code kyber.nsf}). */
  static boolean isDefaultNsfPath(String nsfPath) {
    return fileName(nsfPath).equalsIgnoreCase(fileName(DesignDefaults.defaultNsfPath()));
  }

  private static String fileName(String nsfPath) {
    String path = nsfPath == null ? "" : nsfPath.trim().replace('\\', '/');
    int bang = path.lastIndexOf("!!");
    if (bang >= 0) {
      path = path.substring(bang + 2);
    }
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  /**
   * Notes {@code ERR_NOEXIST} (259). Low 14 bits cover a packaged status word.
   */
  private static final int ERR_NOEXIST = 259;

  /**
   * Creates {@code kyber.nsf} when the file is missing. Writes the title, ACL, and the same usage
   * text in About and Using, and Infoware's logo as the database icon. The Notes client opens
   * About each time the database is opened. No design templates are written. The logo is not a
   * template.
   */
  static void ensureEmptyDefaultsNsf(KeepJnxSession session, DominoClient.Encryption encryption)
      throws Exception {
    String path = DesignDefaults.defaultNsfPath();
    DominoClient.Encryption chosen =
        encryption == null ? DominoClient.Encryption.None : encryption;
    if (!openOrCreate(session, path, chosen)) {
      return;
    }
    Database db = session.getDatabaseDomino(path);
    String title = defaultTitleForNewNsf(path);
    if (title != null) {
      db.setTitle(title);
    }
    DxlImporter importer = session.getDxlImporter();
    DesignElementImport.importOne(importer, db, "about", DefaultsDatabaseHelp.aboutDxl(), session);
    DesignElementImport.importOne(importer, db, "using", DefaultsDatabaseHelp.usingDxl(), session);
    DxlDesignPatch.importLaunchSettings(importer, DefaultsDatabaseHelp.notesLaunchAboutDxl(), db);
    applyProductDbIcon(importer, db, session);
  }

  private static void applyProductDbIcon(DxlImporter importer, Database db, KeepJnxSession session)
      throws Exception {
    String iconDxl =
        DesignDefaults.productDbIconDxl()
            .orElseThrow(() -> new IllegalStateException("Embedded Kyber database icon is missing"));
    DesignElementImport.importOne(importer, db, "image", iconDxl, session);
  }

  /**
   * @return true if the NSF was created
   */
  private static boolean openOrCreate(
      KeepJnxSession session, String nsfPath, DominoClient.Encryption encryption) throws Exception {
    DominoClient client = session.getClient();
    String server = "";
    String path = nsfPath;
    int bang = nsfPath.indexOf("!!");
    if (bang >= 0) {
      server = nsfPath.substring(0, bang);
      path = nsfPath.substring(bang + 2);
    }

    try {
      client.openDatabase(server, path);
      return false;
    } catch (Exception openFailed) {
      if (!isMissingDatabase(openFailed)) {
        String message =
            openFailed.getMessage() == null ? openFailed.toString() : openFailed.getMessage();
        throw new KeepExceptionWrongDataRequest(
            "Cannot open "
                + nsfPath
                + " ("
                + message
                + "). A new NSF is created only when the file does not exist.");
      }
    }

    DesignAccess.requireCreateDatabase(session);
    Database created = client.createDatabase(server, path, false, true, encryption);
    if (isDefaultNsfPath(path)) {
      DesignAcl.ensureCreatedAcl(created);
    } else {
      String host = created.getServer();
      if (host == null || host.isBlank()) {
        host = server;
      }
      DesignAcl.ensureApplicationAcl(created, client.getEffectiveUserName(), host);
    }
    return true;
  }

  /**
   * True only for a missing file. Authorization and other open failures must not create an NSF.
   */
  static boolean isMissingDatabase(Throwable error) {
    for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
      if (cursor instanceof DominoException domino) {
        int id = domino.getId();
        if (id == ERR_NOEXIST || (id & 0x3fff) == ERR_NOEXIST) {
          return true;
        }
      }
      String message = cursor.getMessage();
      if (message == null) {
        continue;
      }
      String lower = message.toLowerCase(Locale.ROOT);
      if (lower.contains("not authorized")
          || lower.contains("access denied")
          || lower.contains("you are not")
          || lower.contains("insufficient access")) {
        return false;
      }
      if (lower.contains("does not exist") || lower.contains("file not found")) {
        return true;
      }
    }
    return false;
  }

  /** Blank means {@link DominoClient.Encryption#None}, the historical create default. */
  static DominoClient.Encryption encryptionOf(String raw) throws KeepExceptionWrongDataRequest {
    if (raw == null || raw.isBlank()) {
      return DominoClient.Encryption.None;
    }
    String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
    return switch (key) {
      case "none" -> DominoClient.Encryption.None;
      case "simple" -> DominoClient.Encryption.Simple;
      case "medium" -> DominoClient.Encryption.Medium;
      case "strong" -> DominoClient.Encryption.Strong;
      case "aes128" -> DominoClient.Encryption.AES128;
      case "aes256" -> DominoClient.Encryption.AES256;
      default ->
          throw new KeepExceptionWrongDataRequest(
              "Unknown encryption '"
                  + raw.trim()
                  + "'; expected none|simple|medium|strong|aes128|aes256");
    };
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
  }
}
