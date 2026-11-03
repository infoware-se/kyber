package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** List names — GET collection path (e.g. /agents)?nsfPath= */
@MinimumAcl(AclLevel.DESIGNER)
public class ListAgents extends KyberRequest {

  private static final Pattern TRIGGER =
      Pattern.compile("(?i)\\b(?:trigger|type|run)=(['\"])(.*?)\\1");
  private static final Pattern SCHEDULE =
      Pattern.compile("(?is)<(?:schedule|trigger)\\b([^>]*)>");

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    Database db = request.db();
    DxlExporter exporter = db.getParentDominoClient().createDxlExporter();
    List<JsonObject> rows = new ArrayList<>();
    var it = db.getDesign().getAgents().iterator();
    while (it.hasNext()) {
      try {
        DesignElement.NamedDesignElement el = it.next();
        String name = DesignDxl.unwrapAgentTitle(el.getTitle());
        if (name == null || name.isBlank()) {
          continue;
        }
        String dxl = exporter.exportDocument(el.getDocument());
        JsonObject row = DesignListing.row(name, el, AgentKinds.detect(dxl));
        String trigger = detectTrigger(dxl);
        if (trigger != null) {
          row.put("trigger", trigger);
        }
        rows.add(row);
      } catch (RuntimeException skipped) {
        // one bad agent must not fail the list
      }
    }
    rows.sort(Comparator.comparing(r -> r.getString("name"), String.CASE_INSENSITIVE_ORDER));
    JsonArray names = new JsonArray();
    JsonArray elements = new JsonArray();
    for (JsonObject row : rows) {
      names.add(row.getString("name"));
      elements.add(row);
    }
    request.emit(new JsonObject().put("Agents", names).put("elements", elements));
  }

  static String detectTrigger(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    Matcher sched = SCHEDULE.matcher(dxl);
    if (sched.find()) {
      Matcher attr = TRIGGER.matcher(sched.group(1));
      if (attr.find()) {
        return attr.group(2).trim().toLowerCase(Locale.ROOT);
      }
      return "schedule";
    }
    String lower = dxl.toLowerCase(Locale.ROOT);
    if (lower.contains("trigger=\"manual\"") || lower.contains("trigger='manual'")) {
      return "manual";
    }
    if (lower.contains("trigger=\"agentlist\"") || lower.contains("trigger='agentlist'")) {
      return "agentlist";
    }
    if (lower.contains("<schedule")) {
      return "schedule";
    }
    return null;
  }
}
