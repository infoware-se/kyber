package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesignDefaultsPickTest {

  @Test
  void pickFirstByNameIsCaseInsensitiveAlphabetical() {
    List<JsonObject> matches = new ArrayList<>();
    matches.add(el("zebra"));
    matches.add(el("Alpha"));
    matches.add(el("beta"));
    assertEquals("Alpha", DesignDefaults.pickFirstByName(matches).getString("name"));
  }

  @Test
  void pickFirstByNameTiesBrokenByNaturalOrder() {
    List<JsonObject> matches = new ArrayList<>();
    matches.add(el("newform"));
    matches.add(el("NewForm"));
    // CASE_INSENSITIVE_ORDER equal → naturalOrder picks 'NewForm' before 'newform'
    assertEquals("NewForm", DesignDefaults.pickFirstByName(matches).getString("name"));
  }

  @Test
  void pickByNameSelectsCaseInsensitiveTemplate() {
    List<JsonObject> matches = new ArrayList<>();
    matches.add(el("Alpha"));
    matches.add(el("Meeting"));
    matches.add(el("zebra"));
    assertEquals("Meeting", DesignDefaults.pickByName(matches, "meeting").getString("name"));
  }

  @Test
  void pickByNameReturnsNullWhenMissing() {
    List<JsonObject> matches = new ArrayList<>();
    matches.add(el("Alpha"));
    assertNull(DesignDefaults.pickByName(matches, "Missing"));
  }

  private static JsonObject el(String name) {
    return new JsonObject().put("name", name).put("type", "form").put("dxl", "<form/>");
  }
}
