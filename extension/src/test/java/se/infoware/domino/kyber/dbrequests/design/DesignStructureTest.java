package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignStructureTest {

  @Test
  void fieldsFromFormDxl() {
    String dxl =
        """
        <form name='F'><body><richtext>
        <field type='text' kind='editable' name='Subject'/>
        <field type="number" kind="computed" name="Amount" allowmultivalues='true'/>
        <field type='text' kind='editable' name='Secret' secret='true'></field>
        </richtext></body></form>
        """;
    JsonArray fields = DesignStructure.fieldsFromDxl(dxl);
    assertEquals(3, fields.size());
    assertEquals("Subject", fields.getJsonObject(0).getString("name"));
    assertEquals("text", fields.getJsonObject(0).getString("type"));
    assertEquals("editable", fields.getJsonObject(0).getString("kind"));
    assertEquals("Amount", fields.getJsonObject(1).getString("name"));
    assertEquals("number", fields.getJsonObject(1).getString("type"));
    assertEquals("computed", fields.getJsonObject(1).getString("kind"));
    assertTrue(fields.getJsonObject(1).getBoolean("allowMultiValues"));
    assertTrue(fields.getJsonObject(2).getBoolean("secret"));
  }

  @Test
  void fieldsIgnoreNameless() {
    JsonArray fields =
        DesignStructure.fieldsFromDxl("<form><field type='text' kind='editable'/></form>");
    assertEquals(0, fields.size());
  }

  @Test
  void columnsFromViewDxl() {
    String dxl =
        """
        <view name='V'>
        <column itemname='$0' width='10' resizable='true' hidden='false'>
        <columnheader title='#'/><code event='value'><formula>@DocNumber</formula></code>
        </column>
        <column itemname='Subject' width='30' categorized='true' sort='true'>
        <columnheader title='Subject'/><code event="value"><formula>Subject</formula></code>
        </column>
        </view>
        """;
    JsonArray columns = DesignStructure.columnsFromDxl(dxl);
    assertEquals(2, columns.size());
    JsonObject first = columns.getJsonObject(0);
    assertEquals("$0", first.getString("itemName"));
    assertEquals("#", first.getString("title"));
    assertEquals("@DocNumber", first.getString("formula"));
    assertEquals("10", first.getString("width"));
    assertFalse(first.getBoolean("hidden"));
    assertTrue(first.getBoolean("resizable"));
    JsonObject second = columns.getJsonObject(1);
    assertEquals("Subject", second.getString("itemName"));
    assertEquals("Subject", second.getString("title"));
    assertEquals("Subject", second.getString("formula"));
    assertTrue(second.getBoolean("categorized"));
    assertTrue(second.getBoolean("sort"));
  }

  @Test
  void emptyDxlYieldsEmptyLists() {
    assertEquals(0, DesignStructure.fieldsFromDxl("").size());
    assertEquals(0, DesignStructure.columnsFromDxl(null).size());
  }
}
