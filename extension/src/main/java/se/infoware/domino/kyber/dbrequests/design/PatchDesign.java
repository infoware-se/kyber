package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.dxl.DxlImporter;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PATCH /patch?nsfPath=&type=&name= with JSON {@code comment} and/or {@code alias}. Safe attribute
 * patch only — not arbitrary XPath (binary DXL stays intact).
 */
@MinimumAcl(AclLevel.DESIGNER)
public class PatchDesign extends KyberRequest {

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    JsonObject in = request.incoming;
    String type = in.getString("type");
    if (type == null || type.isBlank()) {
      throw new KeepExceptionWrongDataRequest("type is required");
    }
    String name = RequestParams.requireName(in);
    JsonObject body = patchBody(in);
    boolean hasComment = body.containsKey("comment");
    boolean hasAlias = body.containsKey("alias");
    if (!hasComment && !hasAlias) {
      throw new KeepExceptionWrongDataRequest("comment and/or alias is required");
    }
    String t = DesignBulkTypes.normalize(type);
    Database db = request.db();
    DesignCrud.notFoundIfMissing(db, t, name);
    String dxl =
        DesignElementLookup.exportDxl(db, t, name)
            .orElseThrow(() -> new KeepExceptionWrongDataRequest(t + " not found: " + name));
    if (hasComment) {
      dxl = withComment(dxl, body.getString("comment"));
    }
    if (hasAlias) {
      dxl = DesignDxl.withAlias(dxl, body.getString("alias"));
    }
    DxlImporter importer = request.session().getDxlImporter();
    DesignElementImport.importOne(
        importer, db, t, dxl, request.session(), DxlDesignPatch.Mode.REPLACE, name);
    String after =
        DesignElementLookup.exportDxl(db, t, name)
            .orElseThrow(() -> new KeepExceptionWrongDataRequest(t + " missing after patch: " + name));
    JsonObject out =
        new JsonObject().put("success", true).put("type", t).put("name", name).put("dxl", after);
    DesignPayload.enrich(out, after);
    request.emit(out);
  }

  /** KEEP may nest JSON under {@code body}; also accept flat query/body merge. */
  static JsonObject patchBody(JsonObject incoming) {
    JsonObject out = new JsonObject();
    if (incoming == null) {
      return out;
    }
    if (incoming.containsKey("comment")) {
      out.put("comment", incoming.getValue("comment"));
    }
    if (incoming.containsKey("alias")) {
      out.put("alias", incoming.getValue("alias"));
    }
    JsonObject nested = incoming.getJsonObject("body");
    if (nested != null) {
      if (nested.containsKey("comment")) {
        out.put("comment", nested.getValue("comment"));
      }
      if (nested.containsKey("alias")) {
        out.put("alias", nested.getValue("alias"));
      }
    }
    return out;
  }

  /** Set or replace root {@code comment} without a DOM round-trip. */
  static String withComment(String dxl, String comment) {
    if (dxl == null || dxl.isBlank()) {
      return dxl;
    }
    Matcher root = Pattern.compile("(?s)<([A-Za-z][\\w:.-]*)\\b([^>]*)>").matcher(dxl);
    if (!root.find()) {
      return dxl;
    }
    String attrs = root.group(2);
    Matcher commentAttr = Pattern.compile("(?i)\\bcomment=(['\"])(.*?)\\1").matcher(attrs);
    String updated;
    if (comment == null) {
      if (!commentAttr.find()) {
        return dxl;
      }
      int start = commentAttr.start();
      int end = commentAttr.end();
      if (start > 0 && Character.isWhitespace(attrs.charAt(start - 1))) {
        start--;
      }
      updated = attrs.substring(0, start) + attrs.substring(end);
    } else if (commentAttr.find()) {
      String quote = commentAttr.group(1);
      updated =
          attrs.substring(0, commentAttr.start())
              + "comment="
              + quote
              + xmlEscape(comment, quote)
              + quote
              + attrs.substring(commentAttr.end());
    } else {
      updated = attrs + " comment='" + xmlEscape(comment, "'") + "'";
    }
    return dxl.substring(0, root.start())
        + "<"
        + root.group(1)
        + updated
        + ">"
        + dxl.substring(root.end());
  }

  private static String xmlEscape(String value, String quote) {
    if (value == null) {
      return "";
    }
    String out =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    if ("\"".equals(quote)) {
      out = out.replace("\"", "&quot;");
    } else {
      out = out.replace("'", "&apos;");
    }
    return out;
  }
}
