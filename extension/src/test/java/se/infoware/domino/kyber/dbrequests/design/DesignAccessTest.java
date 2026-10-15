package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesignAccessTest {

  @Test
  void deleteDocumentsCheckboxBlocksDesignerAndManager() {
    assertFalse(DesignAccess.allowsDelete(EnumSet.of(AclFlag.NODELETE, AclFlag.PERSON)));
    assertTrue(DesignAccess.allowsDelete(EnumSet.of(AclFlag.PERSON, AclFlag.CREATE_FOLDER)));
    assertTrue(DesignAccess.allowsDelete(Set.of()));
  }

  @Test
  void writeCheckboxesAreRequiredWhateverTheAccessLevel() {
    assertEquals(
        Set.of(AclFlag.CREATE_LOTUSSCRIPT),
        DesignAccess.requiredWriteFlags("agent", "<agent><lotusscript/></agent>"));
    assertEquals(
        Set.of(AclFlag.CREATE_LOTUSSCRIPT),
        DesignAccess.requiredWriteFlags("agent", "<agent><javaproject/></agent>"));
    assertEquals(
        Set.of(AclFlag.CREATE_PRAGENT, AclFlag.CREATE_LOTUSSCRIPT),
        DesignAccess.requiredWriteFlags(
            "agent", "<agent private='true'><lotusscript/></agent>"));
    assertEquals(
        Set.of(AclFlag.CREATE_PRAGENT),
        DesignAccess.requiredWriteFlags("agent", "<agent private='true'><formula/></agent>"));
    assertEquals(Set.of(), DesignAccess.requiredWriteFlags("agent", "<agent><formula/></agent>"));
    assertEquals(
        Set.of(AclFlag.CREATE_FOLDER),
        DesignAccess.requiredWriteFlags("view", "<view name='SmokeView'/>"));
    assertEquals(
        Set.of(AclFlag.CREATE_FOLDER),
        DesignAccess.requiredWriteFlags("folder", "<folder name='SmokeFolder'/>"));
    assertEquals(
        Set.of(AclFlag.CREATE_PRFOLDER),
        DesignAccess.requiredWriteFlags("view", "<view private=\"true\"/>"));
    assertEquals(Set.of(), DesignAccess.requiredWriteFlags("form", "<form/>"));
  }

  @Test
  void managerKeepsDesignCreationBoxesThatDominoDoesNotStore() {
    Set<AclFlag> stored = EnumSet.of(AclFlag.PERSON, AclFlag.NODELETE);
    Set<AclFlag> manager = DesignAccess.withManagerImpliedFlags(AclLevel.MANAGER, stored);
    assertTrue(manager.contains(AclFlag.CREATE_FOLDER));
    assertTrue(manager.contains(AclFlag.CREATE_PRFOLDER));
    assertTrue(manager.contains(AclFlag.CREATE_LOTUSSCRIPT));
    assertTrue(manager.contains(AclFlag.CREATE_PRAGENT));
    assertTrue(manager.contains(AclFlag.NODELETE));
    assertFalse(DesignAccess.allowsDelete(manager));

    Set<AclFlag> designer = DesignAccess.withManagerImpliedFlags(AclLevel.DESIGNER, stored);
    assertFalse(designer.contains(AclFlag.CREATE_FOLDER));
    assertFalse(designer.contains(AclFlag.CREATE_LOTUSSCRIPT));
  }
}
