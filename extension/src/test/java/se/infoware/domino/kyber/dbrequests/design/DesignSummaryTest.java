package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignSummaryTest {

  @Test
  void formSummaryIncludesFieldCountAndAliases() {
    String dxl =
        """
        <form name='Main|AliasOne|AliasTwo' comment='Hello'>
        <field type='text' kind='editable' name='Subject'/>
        <field type='text' kind='editable' name='Body'/>
        </form>
        """;
    JsonObject out = DesignSummary.fromDxl("form", "Main", dxl, "368a", "AABB");
    assertEquals("form", out.getString("type"));
    assertEquals("Main", out.getString("name"));
    assertEquals(2, out.getInteger("fieldCount"));
    assertEquals("Hello", out.getString("comment"));
    assertEquals(2, out.getJsonArray("aliases").size());
    assertFalse(out.containsKey("dxl"));
  }

  @Test
  void viewSummaryIncludesColumnsAndForms() {
    String dxl =
        """
        <view name='All' form='Main'>
        <column itemname='$0' width='10'><columnheader title='#'/>
        <code event='value'><formula>@DocNumber</formula></code></column>
        </view>
        """;
    JsonObject out = DesignSummary.fromDxl("view", "All", dxl, null, null);
    assertEquals(1, out.getInteger("columnCount"));
    assertEquals("Main", out.getJsonArray("forms").getString(0));
  }

  @Test
  void agentSummaryIncludesTrigger() {
    String dxl =
        """
        <agent name='Nightly'><trigger type='schedule'/><schedule enabled='true' type='daily' starthour='2'/>
        </agent>
        """;
    JsonObject out = DesignSummary.fromDxl("agent", "Nightly", dxl, null, null);
    assertEquals("schedule", out.getString("trigger"));
    assertEquals("true", out.getJsonObject("schedule").getString("enabled"));
    assertEquals("daily", out.getJsonObject("schedule").getString("type"));
  }
}
