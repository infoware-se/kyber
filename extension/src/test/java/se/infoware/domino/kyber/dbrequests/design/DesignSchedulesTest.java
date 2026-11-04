package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignSchedulesTest {

  @Test
  void scheduledAgentIncludesTriggerAndSchedule() {
    String dxl =
        """
        <agent name='Nightly' enabled='true'>
        <trigger type='schedule'/>
        <schedule enabled='true' type='daily' starthour='2' endhour='4' runon='server'/>
        </agent>
        """;
    JsonObject row = DesignSchedules.rowFromDxl("Nightly", dxl);
    assertEquals("Nightly", row.getString("name"));
    assertEquals("schedule", row.getString("trigger"));
    assertEquals("daily", row.getJsonObject("schedule").getString("type"));
    assertEquals("2", row.getJsonObject("schedule").getString("startHour"));
    assertEquals("server", row.getJsonObject("schedule").getString("runOn"));
  }

  @Test
  void manualAgentHasTriggerWithoutSchedule() {
    String dxl = "<agent name='OnDemand' trigger='manual'></agent>";
    JsonObject row = DesignSchedules.rowFromDxl("OnDemand", dxl);
    assertEquals("manual", row.getString("trigger"));
    assertFalse(row.containsKey("schedule"));
  }

  @Test
  void blankDxlYieldsNameOnly() {
    JsonObject row = DesignSchedules.rowFromDxl("Empty", "");
    assertEquals("Empty", row.getString("name"));
    assertNull(row.getString("trigger"));
    assertFalse(row.containsKey("schedule"));
  }

  @Test
  void scheduleOfParsesDays() {
    JsonObject schedule =
        DesignSummary.scheduleOf(
            "<schedule enabled='true' type='weekly' days='1,3,5' time='09:00'/>");
    assertTrue(schedule != null);
    assertEquals("weekly", schedule.getString("type"));
    assertEquals("1,3,5", schedule.getString("days"));
    assertEquals("09:00", schedule.getString("time"));
  }
}
