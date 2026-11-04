package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.infoware.domino.kyber.KyberLog;

/**
 * Domino importer pass for {@code POST /validate}. JNX has no validate-only flag, so the DXL is
 * imported into a throwaway NSF next to the target, then that file is deleted. The target NSF is
 * never written. Creating the scratch file needs the server privilege Create databases &amp;
 * templates.
 */
final class DesignValidateDryRun {

  private static final Logger LOG = KyberLog.of(DesignValidateDryRun.class);

  private static final Pattern WARNING_TAG =
      Pattern.compile("(?is)<warning\\b[^>]*>(.*?)</warning>");

  private DesignValidateDryRun() {}

  static final class Result {
    final JsonArray errors = new JsonArray();
    final JsonArray warnings = new JsonArray();
    String importerLog;
  }

  /**
   * Run the importer against a scratch NSF for every non-delete element that already passed {@link
   * DesignImportCheck}. Delete rows are checked for existence on {@code target} only.
   */
  static Result run(
      KeepJnxSession session, Database target, JsonArray elements, boolean[] precheckOk)
      throws Exception {
    Result out = new Result();
    if (elements == null || elements.isEmpty()) {
      return out;
    }

    boolean needImporter = false;
    for (int i = 0; i < elements.size(); i++) {
      if (!precheckOk[i]) {
        continue;
      }
      Object value = elements.getValue(i);
      if (!(value instanceof JsonObject element)) {
        continue;
      }
      if (element.getBoolean("delete", false)) {
        String type = element.getString("type", "");
        String name = element.getString("name");
        try {
          if (!DesignElementLookup.exists(target, type, name)) {
            out.errors.add(
                DesignValidate.errorRow(
                    i,
                    element,
                    DesignBulkTypes.normalize(type) + " not found: " + name));
          }
        } catch (Exception e) {
          String message = e.getMessage() == null ? e.toString() : e.getMessage();
          out.errors.add(DesignValidate.errorRow(i, element, message));
        }
      } else {
        needImporter = true;
      }
    }
    if (!needImporter) {
      return out;
    }

    String targetPath =
        target.getRelativeFilePath() == null ? "" : target.getRelativeFilePath().trim();
    String scratchPath = scratchPathBeside(targetPath);
    String[] where = ImportDesign.serverAndPath(scratchPath);
    DominoClient client = session.getClient();

    try {
      DesignAccess.requireCreateDatabase(session);
    } catch (KeepException e) {
      out.errors.add(
          DesignValidate.errorRow(
              0,
              null,
              "Importer dry-run needs Create databases & templates (scratch NSF): "
                  + (e.getMessage() == null ? e.toString() : e.getMessage())));
      // Force a stable code even when the privilege message does not match other patterns.
      JsonObject row = out.errors.getJsonObject(0);
      row.put("code", "dry_run_unavailable");
      row.put("type", "");
      return out;
    } catch (Exception e) {
      out.errors.add(
          DesignValidate.errorRow(
              0,
              null,
              "Importer dry-run could not check Create databases & templates: "
                  + (e.getMessage() == null ? e.toString() : e.getMessage())));
      out.errors.getJsonObject(0).put("code", "dry_run_unavailable").put("type", "");
      return out;
    }

    Database scratch = null;
    try {
      scratch =
          client.createDatabase(where[0], where[1], false, true, DominoClient.Encryption.None);
      String host = scratch.getServer();
      if (host == null || host.isBlank()) {
        host = where[0];
      }
      DesignAcl.ensureApplicationAcl(scratch, client.getEffectiveUserName(), host);

      DxlImporter importer = session.getDxlImporter();
      StringBuilder log = new StringBuilder();
      for (int i = 0; i < elements.size(); i++) {
        if (!precheckOk[i]) {
          continue;
        }
        Object value = elements.getValue(i);
        if (!(value instanceof JsonObject element)) {
          continue;
        }
        if (element.getBoolean("delete", false)) {
          continue;
        }
        try {
          DesignElementImport.dryRunImport(
              importer,
              scratch,
              element.getString("type", ""),
              element.getString("dxl", ""),
              element.getString("name"));
        } catch (Exception e) {
          String message = e.getMessage() == null ? e.toString() : e.getMessage();
          JsonObject row = DesignValidate.errorRow(i, element, message);
          row.put("code", "importer_error");
          out.errors.add(row);
        }
        String piece = importer.getResultLog();
        if (piece != null && !piece.isBlank()) {
          if (log.length() > 0) {
            log.append('\n');
          }
          log.append(piece.trim());
          appendWarnings(out.warnings, i, element, piece);
        }
      }
      out.importerLog = ImportDesign.truncateLog(log.toString(), 8000);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      JsonObject row =
          DesignValidate.errorRow(
              0, null, "Importer dry-run could not use a scratch NSF: " + message);
      row.put("code", "dry_run_unavailable");
      row.put("type", "");
      out.errors.add(row);
    } finally {
      if (scratch != null) {
        try {
          scratch.close();
        } catch (Exception ignored) {
          // best effort
        }
      }
      try {
        client.deleteDatabase(where[0], where[1]);
      } catch (Exception cleanup) {
        KyberLog.warn(
            LOG,
            "validate scratch delete failed path="
                + scratchPath
                + " "
                + (cleanup.getMessage() == null ? cleanup.toString() : cleanup.getMessage()));
      }
    }
    return out;
  }

  static String scratchPathBeside(String targetNsfPath) {
    String[] where = ImportDesign.serverAndPath(targetNsfPath == null ? "" : targetNsfPath);
    String path = where[1].replace('\\', '/');
    int slash = path.lastIndexOf('/');
    String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
    String name =
        "kyber_vdry_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT)
            + ".nsf";
    String file = dir + name;
    if (where[0] != null && !where[0].isBlank()) {
      return where[0] + "!!" + file;
    }
    return file;
  }

  static void appendWarnings(JsonArray warnings, int index, JsonObject element, String log) {
    if (log == null || log.isBlank()) {
      return;
    }
    Matcher matcher = WARNING_TAG.matcher(log);
    boolean found = false;
    while (matcher.find()) {
      found = true;
      String text = flattenXmlText(matcher.group(1));
      if (text.isEmpty()) {
        continue;
      }
      warnings.add(warningRow(index, element, text));
    }
    if (!found) {
      // Plain-text logs from some Domino builds
      for (String line : log.split("\\R")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("warning") || lower.contains(": warning")) {
          warnings.add(warningRow(index, element, trimmed));
        }
      }
    }
  }

  private static JsonObject warningRow(int index, JsonObject element, String message) {
    JsonObject row =
        new JsonObject()
            .put("index", index)
            .put("type", element == null ? "" : element.getString("type", ""))
            .put("message", message);
    if (element != null) {
      String name = element.getString("name");
      if (name != null && !name.isBlank()) {
        row.put("name", name);
      }
    }
    return row;
  }

  private static String flattenXmlText(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
  }
}
