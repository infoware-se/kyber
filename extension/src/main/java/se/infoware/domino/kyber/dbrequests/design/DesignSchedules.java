package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.dxl.DxlExporter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * NSF-wide agent trigger/schedule inventory without full DXL. Used by {@code GET /schedules}.
 */
final class DesignSchedules {

  private DesignSchedules() {}

  static JsonObject inventory(Database db, JsonObject incoming) throws Exception {
    String triggerFilter = blankToNull(incoming == null ? null : incoming.getString("trigger"));
    if (triggerFilter != null) {
      triggerFilter = triggerFilter.trim().toLowerCase(Locale.ROOT);
    }
    boolean scheduledOnly = scheduledOnly(incoming);

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
        String trigger = ListAgents.detectTrigger(dxl);
        JsonObject schedule = DesignSummary.scheduleOf(dxl);
        if (scheduledOnly) {
          boolean isScheduled =
              "schedule".equals(trigger) || (schedule != null && !schedule.isEmpty());
          if (!isScheduled) {
            continue;
          }
        }
        if (triggerFilter != null) {
          if (trigger == null || !triggerFilter.equals(trigger)) {
            continue;
          }
        }
        JsonObject row = DesignListing.row(name, el, AgentKinds.detect(dxl));
        if (trigger != null) {
          row.put("trigger", trigger);
        }
        if (schedule != null && !schedule.isEmpty()) {
          row.put("schedule", schedule);
        }
        rows.add(row);
      } catch (RuntimeException skipped) {
        // one bad agent must not fail the inventory
      }
    }
    rows.sort(Comparator.comparing(r -> r.getString("name"), String.CASE_INSENSITIVE_ORDER));
    JsonArray agents = new JsonArray();
    for (JsonObject row : rows) {
      agents.add(row);
    }
    return new JsonObject().put("count", agents.size()).put("agents", agents);
  }

  /** Package-visible for unit tests — build one inventory row from DXL. */
  static JsonObject rowFromDxl(String name, String dxl) {
    JsonObject row = new JsonObject().put("name", name);
    String kind = AgentKinds.detect(dxl);
    if (kind != null && !kind.isBlank()) {
      row.put("kind", kind);
    }
    String trigger = ListAgents.detectTrigger(dxl);
    if (trigger != null) {
      row.put("trigger", trigger);
    }
    JsonObject schedule = DesignSummary.scheduleOf(dxl);
    if (schedule != null && !schedule.isEmpty()) {
      row.put("schedule", schedule);
    }
    return row;
  }

  private static boolean scheduledOnly(JsonObject incoming) {
    if (incoming == null || !incoming.containsKey("scheduledOnly")) {
      return false;
    }
    Object v = incoming.getValue("scheduledOnly");
    if (v instanceof Boolean b) {
      return b;
    }
    return "true".equalsIgnoreCase(String.valueOf(v));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
