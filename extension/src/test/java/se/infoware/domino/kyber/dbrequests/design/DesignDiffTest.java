package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignDiffTest {

  @Test
  void identicalAfterNormalize() {
    String left =
        "<form name='A' noteid='1' unid='U1' comment='Hi'><field name='S' type='text' kind='editable'/></form>";
    String right =
        "<form name='A' noteid='2' unid='U2' comment='Hi'><field name='S' type='text' kind='editable'/></form>";
    JsonObject out =
        DesignDiff.compare(
            new DesignDiff.Side("form", "A", left, "note"),
            new DesignDiff.Side("form", "A", right, "dxl"));
    assertTrue(out.getBoolean("identical"));
    assertEquals(0, out.getInteger("changeCount"));
  }

  @Test
  void fieldAddAndCommentChange() {
    String left =
        "<form name='A' comment='Old'><field name='S' type='text' kind='editable'/></form>";
    String right =
        "<form name='A' comment='New'><field name='S' type='text' kind='editable'/><field name='T' type='text' kind='editable'/></form>";
    JsonObject out =
        DesignDiff.compare(
            new DesignDiff.Side("form", "A", left, "note"),
            new DesignDiff.Side("form", "A", right, "dxl"));
    assertFalse(out.getBoolean("identical"));
    JsonArray changes = out.getJsonArray("changes");
    assertTrue(changes.size() >= 2);
    boolean sawFieldAdd = false;
    boolean sawComment = false;
    for (int i = 0; i < changes.size(); i++) {
      JsonObject c = changes.getJsonObject(i);
      if ("fields".equals(c.getString("path")) && "add".equals(c.getString("op"))) {
        sawFieldAdd = "T".equals(c.getString("name"));
      }
      if ("comment".equals(c.getString("path"))) {
        sawComment = true;
        assertEquals("Old", c.getString("left"));
        assertEquals("New", c.getString("right"));
      }
    }
    assertTrue(sawFieldAdd);
    assertTrue(sawComment);
  }

  @Test
  void lineHunksShowReplace() {
    JsonArray hunks = DesignDiff.lineHunks("a\nb\nc\n", "a\nB\nc\n");
    assertTrue(hunks.size() >= 1);
    JsonArray lines = hunks.getJsonObject(0).getJsonArray("lines");
    boolean sawMinus = false;
    boolean sawPlus = false;
    for (int i = 0; i < lines.size(); i++) {
      String op = lines.getJsonObject(i).getString("op");
      if ("-".equals(op)) {
        sawMinus = true;
      }
      if ("+".equals(op)) {
        sawPlus = true;
      }
    }
    assertTrue(sawMinus && sawPlus);
  }

  @Test
  void normalizeStripsVolatileAttrs() {
    String n =
        DesignDiff.normalizeForDiff(
            "<form name='A' noteid='1' unid='UU'><item name='$DesignerBuild'><text>x</text></item></form>");
    assertFalse(n.contains("noteid"));
    assertFalse(n.contains("unid"));
    assertFalse(n.contains("DesignerBuild"));
  }
}
