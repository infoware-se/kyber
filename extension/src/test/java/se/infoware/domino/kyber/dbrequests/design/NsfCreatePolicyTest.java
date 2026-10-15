package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoException;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class NsfCreatePolicyTest {

  @Test
  void missingFileMayBeCreated() {
    assertTrue(
        ImportDesignDxl.isMissingDatabase(new DominoException(259, "File does not exist")));
    assertTrue(ImportDesignDxl.isMissingDatabase(new RuntimeException("File not found")));
  }

  @Test
  void otherOpenFailuresAreNotTreatedAsMissing() {
    assertFalse(
        ImportDesignDxl.isMissingDatabase(
            new DominoException(1280, "You are not authorized to perform that operation")));
    assertFalse(ImportDesignDxl.isMissingDatabase(new RuntimeException("database is locked")));
  }

  @Test
  void encryptionDefaultsToNone() throws Exception {
    assertEquals(DominoClient.Encryption.None, ImportDesignDxl.encryptionOf(null));
    assertEquals(DominoClient.Encryption.None, ImportDesignDxl.encryptionOf("  "));
    assertEquals(DominoClient.Encryption.AES256, ImportDesignDxl.encryptionOf("AES-256"));
  }

  @Test
  void knownNsfsGetATitleWhenCreateOmitsDbTitle() {
    assertEquals("Kyber Design Elements", ImportDesignDxl.defaultTitleForNewNsf("kyber.nsf"));
    assertEquals(
        "Kyber Design Elements", ImportDesignDxl.defaultTitleForNewNsf("Server!!kyber.nsf"));
    assertEquals("Kyber Smoke Test", ImportDesignDxl.defaultTitleForNewNsf("kybertest.nsf"));
    assertNull(ImportDesignDxl.defaultTitleForNewNsf("other.nsf"));
    assertTrue(ImportDesignDxl.isDefaultNsfPath("kyber.nsf"));
    assertTrue(ImportDesignDxl.isDefaultNsfPath("Server!!kyber.nsf"));
    assertFalse(ImportDesignDxl.isDefaultNsfPath("kybertest.nsf"));
  }

  @Test
  void newNsfAclMatchesTheCurrentKyberDatabase() {
    var entries = DesignAcl.createdAclEntries();
    assertEquals(
        java.util.List.of(
            "-Default-",
            "Anonymous",
            "OtherDomainServers",
            "LocalKeepAdmins",
            "LocalDomainAdmins",
            "LocalDomainServers"),
        entries.stream().map(DesignAcl.StarterEntry::name).toList());
    assertEquals(AclLevel.NOACCESS, level(entries, "-Default-"));
    assertEquals(AclLevel.NOACCESS, level(entries, "Anonymous"));
    assertEquals(AclLevel.NOACCESS, level(entries, "OtherDomainServers"));
    assertEquals(AclLevel.MANAGER, level(entries, "LocalKeepAdmins"));
    assertEquals(AclLevel.MANAGER, level(entries, "LocalDomainAdmins"));
    assertEquals(AclLevel.MANAGER, level(entries, "LocalDomainServers"));
    assertTrue(flags(entries, "LocalKeepAdmins").contains(AclFlag.GROUP));
    assertTrue(flags(entries, "LocalDomainAdmins").contains(AclFlag.PERSON));
    assertTrue(flags(entries, "LocalDomainServers").contains(AclFlag.SERVER));
    assertFalse(flags(entries, "LocalKeepAdmins").contains(AclFlag.NODELETE));
    assertFalse(flags(entries, "LocalDomainAdmins").contains(AclFlag.NOREPLICATE));
  }

  @Test
  void otherNewNsfAclUsesTheCreatorAndTheServer() {
    var entries =
        DesignAcl.applicationAclEntries("CN=Creator/O=Example", "CN=Server/O=Example");
    assertEquals(AclLevel.AUTHOR, level(entries, "-Default-"));
    assertTrue(flags(entries, "-Default-").contains(AclFlag.CREATE_PRFOLDER));
    assertEquals(AclLevel.NOACCESS, level(entries, "Anonymous"));
    assertEquals(AclLevel.NOACCESS, level(entries, "OtherDomainServers"));
    assertEquals(AclLevel.MANAGER, level(entries, "CN=Creator/O=Example"));
    assertTrue(flags(entries, "CN=Creator/O=Example").contains(AclFlag.PERSON));
    assertEquals(AclLevel.MANAGER, level(entries, "CN=Server/O=Example"));
    assertTrue(flags(entries, "CN=Server/O=Example").contains(AclFlag.SERVER));
    assertEquals(List.of("Configuration"), roles(entries, "LocalDomainAdmins"));
    assertTrue(flags(entries, "LocalDomainServers").contains(AclFlag.GROUP));
    assertFalse(
        entries.stream().map(DesignAcl.StarterEntry::name).anyMatch("LocalKeepAdmins"::equals));

    var same =
        DesignAcl.applicationAclEntries("CN=Server/O=Example", "CN=Server/O=Example");
    assertEquals(1, same.stream().filter(e -> e.name().equals("CN=Server/O=Example")).count());
    assertTrue(flags(same, "CN=Server/O=Example").contains(AclFlag.SERVER));
  }

  private static AclLevel level(java.util.List<DesignAcl.StarterEntry> entries, String name) {
    return entries.stream()
        .filter(entry -> entry.name().equals(name))
        .findFirst()
        .orElseThrow()
        .level();
  }

  private static java.util.Collection<AclFlag> flags(
      java.util.List<DesignAcl.StarterEntry> entries, String name) {
    return entries.stream()
        .filter(entry -> entry.name().equals(name))
        .findFirst()
        .orElseThrow()
        .flags();
  }

  private static List<String> roles(java.util.List<DesignAcl.StarterEntry> entries, String name) {
    return entries.stream()
        .filter(entry -> entry.name().equals(name))
        .findFirst()
        .orElseThrow()
        .roles();
  }

  @Test
  void writesRequireTheTargetAcl() {
    JsonArray design =
        new JsonArray().add(new JsonObject().put("type", "form").put("dxl", "<form/>"));
    JsonArray acl =
        new JsonArray().add(new JsonObject().put("type", "acl").put("dxl", "<acl/>"));
    JsonArray both = design.copy().add(acl.getJsonObject(0));
    assertEquals(AclLevel.DESIGNER, ImportDesignDxl.requiredWriteLevel(design));
    assertEquals(AclLevel.MANAGER, ImportDesignDxl.requiredWriteLevel(acl));
    assertEquals(AclLevel.MANAGER, ImportDesignDxl.requiredWriteLevel(both));
    assertNull(ImportDesignDxl.requiredWriteLevel(new JsonArray()));
    assertNull(ImportDesignDxl.requiredWriteLevel(null));
  }

  @Test
  void newNsfGetsEmbeddedIconUnlessThePayloadSuppliesOne() {
    JsonArray forms =
        new JsonArray().add(new JsonObject().put("type", "form").put("dxl", "<form/>"));
    JsonArray ownIcon =
        new JsonArray()
            .add(new JsonObject().put("type", "dbicon").put("dxl", "<imageresource/>"));
    JsonArray ownImage =
        new JsonArray()
            .add(
                new JsonObject()
                    .put("type", "image")
                    .put("dxl", "<imageresource name='$DBIcon'/>"));
    assertTrue(ImportDesignDxl.shouldApplyDefaultDbIcon(true, "app.nsf", forms));
    assertTrue(ImportDesignDxl.shouldApplyDefaultDbIcon(true, "kyber.nsf", forms));
    assertTrue(ImportDesignDxl.shouldApplyDefaultDbIcon(true, "Server!!kyber.nsf", forms));
    assertFalse(ImportDesignDxl.shouldApplyDefaultDbIcon(false, "app.nsf", forms));
    assertFalse(ImportDesignDxl.shouldApplyDefaultDbIcon(true, "app.nsf", ownIcon));
    assertFalse(ImportDesignDxl.shouldApplyDefaultDbIcon(true, "app.nsf", ownImage));
    assertFalse(
        DesignDefaults.countsAsStarter(
            new JsonObject().put("type", "image").put("name", "$DBIcon")));
    assertTrue(
        DesignDefaults.countsAsStarter(new JsonObject().put("type", "form").put("name", "Main")));
  }

  @Test
  void newKyberDatabaseExplainsItselfInAboutAndUsing() {
    String about = DefaultsDatabaseHelp.aboutDxl();
    String using = DefaultsDatabaseHelp.usingDxl();
    assertTrue(about.contains("<helpaboutdocument"));
    assertTrue(using.contains("<helpusingdocument"));
    assertTrue(about.contains("Kyber Design Elements"));
    assertTrue(using.contains("Kyber Design Elements"));
    assertTrue(about.contains("size='24pt'"));
    assertTrue(about.contains("size='12pt'"));
    assertTrue(about.contains("leftmargin='1in'"));
    assertTrue(about.contains("leftmargin='1.35in'"));
    assertTrue(about.contains("color='maroon'"));
    assertFalse(about.contains("size='16pt'"));
    String launch = DefaultsDatabaseHelp.notesLaunchAboutDxl();
    assertTrue(launch.contains("<launchsettings>"));
    assertTrue(launch.contains("whenopened='openaboutdocument'"));
    assertFalse(launch.contains("<databaseinfo>"));
    assertTrue(DefaultsDatabaseHelp.isUsageText(about));
    assertTrue(DefaultsDatabaseHelp.isUsageText(using));
    String aboutBody = about.substring(about.indexOf("<par def='1'>"), about.indexOf("</richtext>"));
    String usingBody = using.substring(using.indexOf("<par def='1'>"), using.indexOf("</richtext>"));
    assertEquals(aboutBody, usingBody);
    assertFalse(
        DesignDefaults.countsAsStarter(
            new JsonObject().put("type", "about").put("dxl", about)));
    assertTrue(
        DesignDefaults.countsAsStarter(
            new JsonObject().put("type", "about").put("dxl", "<helpaboutdocument><body>Own</body></helpaboutdocument>")));
  }

  @Test
  void prohibitRefreshFlagIsTheLetterP() {
    assertEquals("34Ci~Q", DbIcon.flagsAllowingRefresh("34Ci~QP"));
    assertEquals("34Ci~Q", DbIcon.flagsAllowingRefresh("34Ci~Q"));
    assertTrue(DbIcon.isImportOnlyFlagsExt("D"));
    assertFalse(DbIcon.isImportOnlyFlagsExt(""));
  }

  @Test
  void unknownEncryptionIsRejected() {
    assertThrows(KeepExceptionWrongDataRequest.class, () -> ImportDesignDxl.encryptionOf("rot13"));
  }
}
