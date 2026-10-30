package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignValidateTest {

  @Test
  void codesAreStableForAgents() {
    assertEquals("invalid_dxl", DesignValidate.codeOf("invalid design DXL: broken"));
    assertEquals("missing_dxl", DesignValidate.codeOf("dxl is required for type form"));
    assertEquals("protected_acl", DesignValidate.codeOf("ACL entry -Default- cannot be removed"));
    assertEquals("protected_acl", DesignValidate.codeOf("LocalDomainServers cannot be removed"));
    assertEquals("unknown_type", DesignValidate.codeOf("Unknown design element type: widget"));
    assertEquals("missing_name", DesignValidate.codeOf("name is required to delete type form"));
    assertEquals("webcontent_path", DesignValidate.codeOf("WebContent under WEB-INF is refused"));
    assertEquals("rejected", DesignValidate.codeOf("something else"));
  }

  @Test
  void errorRowCarriesIndexTypeCodeAndOptionalName() {
    JsonObject row =
        DesignValidate.errorRow(
            2,
            new JsonObject().put("type", "form").put("name", "Contact"),
            "dxl is required for type form");
    assertEquals(2, row.getInteger("index"));
    assertEquals("form", row.getString("type"));
    assertEquals("Contact", row.getString("name"));
    assertEquals("missing_dxl", row.getString("code"));
    assertEquals("dxl is required for type form", row.getString("message"));
  }
}
