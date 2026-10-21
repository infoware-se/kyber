package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

class DesignImportCheckTest {

  @Test
  void wellFormedFormIsAccepted() {
    assertDoesNotThrow(
        () ->
            DesignImportCheck.check(
                new JsonObject()
                    .put("type", "form")
                    .put("dxl", "<form name='Contact' xmlns='http://www.lotus.com/dxl'/>")));
  }

  @Test
  void brokenDxlIsRejected() {
    KeepExceptionWrongDataRequest error =
        assertThrows(
            KeepExceptionWrongDataRequest.class,
            () ->
                DesignImportCheck.check(
                    new JsonObject().put("type", "form").put("name", "Contact").put("dxl", "<form>")));
    assertTrue(error.getMessage().contains("invalid design DXL"));
  }

  @Test
  void aclWithoutDefaultIsRejected() {
    KeepExceptionWrongDataRequest error =
        assertThrows(
            KeepExceptionWrongDataRequest.class,
            () ->
                DesignImportCheck.check(
                    new JsonObject()
                        .put("type", "acl")
                        .put(
                            "dxl",
                            "<acl><aclentry name='LocalDomainServers' level='manager'/></acl>")));
    assertTrue(error.getMessage().contains("-Default-"));
  }

  @Test
  void webContentUnderWebInfIsRejected() {
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () ->
            DesignImportCheck.check(
                new JsonObject()
                    .put("type", "webcontent")
                    .put(
                        "dxl",
                        "<note><item name='$TITLE'><text>WEB-INF/web.xml</text></item></note>")));
  }

  @Test
  void deleteNeedsANameAndCannotRemoveTheAcl() {
    assertDoesNotThrow(
        () ->
            DesignImportCheck.check(
                new JsonObject().put("type", "form").put("name", "Contact").put("delete", true)));
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () ->
            DesignImportCheck.check(new JsonObject().put("type", "form").put("delete", true)));
    assertThrows(
        KeepExceptionWrongDataRequest.class,
        () ->
            DesignImportCheck.check(
                new JsonObject().put("type", "acl").put("name", "acl").put("delete", true)));
  }
}
