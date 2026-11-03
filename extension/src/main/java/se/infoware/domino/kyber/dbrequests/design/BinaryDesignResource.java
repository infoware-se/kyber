package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read/write binary payloads for image, fileresource, jar, and dbicon as base64 JSON — avoids
 * assembling DXL wrappers by hand and never DOM-round-trips {@code $FileData}.
 */
final class BinaryDesignResource {

  private static final Pattern FILEDATA =
      Pattern.compile(
          "(?is)(<item\\s+name=(['\"])\\$FileData\\2[^>]*>\\s*<rawitemdata[^>]*>)(.*?)(</rawitemdata>)");
  private static final Pattern FILENAMES =
      Pattern.compile("(?is)<item\\s+name=(['\"])\\$FileNames\\1[^>]*>\\s*<text>(.*?)</text>");

  private BinaryDesignResource() {}

  static JsonObject get(Database db, String type, String name) throws Exception {
    String t = normalizeBinaryType(type, name);
    String lookup = "dbicon".equals(t) ? DesignElementLookup.DBICON_NAME : name.trim();
    String dxl =
        DesignElementLookup.exportDxl(db, t, lookup)
            .orElseThrow(
                () -> new KeepExceptionWrongDataRequest(t + " not found: " + lookup));
    String b64 = extractFileData(dxl);
    if (b64 == null || b64.isBlank()) {
      throw new KeepExceptionWrongDataRequest(t + " has no $FileData payload: " + lookup);
    }
    // Normalize whitespace from DXL wrapping
    b64 = b64.replaceAll("\\s+", "");
    JsonObject out =
        new JsonObject()
            .put("type", t)
            .put("name", lookup)
            .put("contentBase64", b64)
            .put("encoding", "base64");
    String fileName = null;
    Matcher fn = FILENAMES.matcher(dxl);
    if (fn.find()) {
      fileName = fn.group(2).trim();
      if (!fileName.isEmpty()) {
        out.put("fileName", fileName);
      } else {
        fileName = null;
      }
    }
    String contentType = guessContentType(lookup, fileName);
    if (contentType != null) {
      out.put("contentType", contentType);
    }
    DesignPayload.enrich(out, dxl);
    return out;
  }

  static JsonObject put(
      KeepJnxSession session, Database db, String type, String name, JsonObject body)
      throws Exception {
    String t = normalizeBinaryType(type, name);
    String lookup = "dbicon".equals(t) ? DesignElementLookup.DBICON_NAME : name.trim();
    if (body == null) {
      throw new KeepExceptionWrongDataRequest("JSON body with contentBase64 is required");
    }
    String b64 = body.getString("contentBase64");
    if ((b64 == null || b64.isBlank()) && body.getJsonObject("body") != null) {
      b64 = body.getJsonObject("body").getString("contentBase64");
    }
    if (b64 == null || b64.isBlank()) {
      throw new KeepExceptionWrongDataRequest("contentBase64 is required");
    }
    b64 = b64.replaceAll("\\s+", "");
    try {
      Base64.getDecoder().decode(b64);
    } catch (IllegalArgumentException e) {
      throw new KeepExceptionWrongDataRequest("contentBase64 is not valid base64");
    }
    DesignCrud.notFoundIfMissing(db, t, lookup);
    String dxl =
        DesignElementLookup.exportDxl(db, t, lookup)
            .orElseThrow(
                () -> new KeepExceptionWrongDataRequest(t + " not found: " + lookup));
    String updated = replaceFileData(dxl, b64);
    DxlImporter importer = session.getDxlImporter();
    JsonObject one =
        DesignElementImport.importOne(
            importer, db, t, updated, session, DxlDesignPatch.Mode.REPLACE, lookup);
    return new JsonObject()
        .put("success", true)
        .put("type", t)
        .put("name", lookup)
        .put("noteId", one.getString("noteId"))
        .put("unid", one.getString("unid"))
        .put("bytes", Base64.getDecoder().decode(b64).length);
  }

  private static String normalizeBinaryType(String type, String name)
      throws KeepExceptionWrongDataRequest {
    String t = DesignBulkTypes.normalize(type);
    if ("dbicon".equals(t)
        || (name != null && DesignElementLookup.DBICON_NAME.equalsIgnoreCase(name.trim()))) {
      return "dbicon";
    }
    if (!SetOf(t)) {
      throw new KeepExceptionWrongDataRequest(
          "binary content supports image, fileresource, jar, dbicon — not " + type);
    }
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    return t;
  }

  private static boolean SetOf(String t) {
    return "image".equals(t) || "fileresource".equals(t) || "jar".equals(t) || "dbicon".equals(t);
  }

  static String extractFileData(String dxl) {
    Matcher m = FILEDATA.matcher(dxl);
    return m.find() ? m.group(3) : null;
  }

  static String replaceFileData(String dxl, String base64) throws KeepExceptionWrongDataRequest {
    Matcher m = FILEDATA.matcher(dxl);
    if (!m.find()) {
      throw new KeepExceptionWrongDataRequest("DXL has no $FileData item to replace");
    }
    return dxl.substring(0, m.start(3)) + base64 + dxl.substring(m.end(3));
  }

  private static String guessContentType(String name, String fileName) {
    String n = fileName != null ? fileName : name;
    if (n == null) {
      return null;
    }
    String lower = n.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".gif")) {
      return "image/gif";
    }
    if (lower.endsWith(".png")) {
      return "image/png";
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (lower.endsWith(".jar")) {
      return "application/java-archive";
    }
    return "application/octet-stream";
  }
}
