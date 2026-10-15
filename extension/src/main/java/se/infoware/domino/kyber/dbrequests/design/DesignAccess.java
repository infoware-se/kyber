package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionInsufficientAccess;
import com.hcl.domino.security.AclAccess;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.ServerAclType;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Privileges that stay in force for Designer and Manager.
 *
 * <p>Creating an NSF is a server right ({@link ServerAclType#CREATE_DATABASE}), not an entry in
 * the new file. Deleting a design note needs the database ACL checkbox Delete documents.
 * LotusScript and Java agents, and shared or personal folders and views, need their own
 * checkboxes. Access level does not grant a checkbox that is off.
 */
final class DesignAccess {

  private static final Pattern PRIVATE_ROOT =
      Pattern.compile(
          "(?is)<(?:agent|view|folder)\\b[^>]*\\bprivate\\s*=\\s*['\"]true['\"]");

  private DesignAccess() {}

  /** Server document privilege "Create databases &amp; templates". */
  static void requireCreateDatabase(KeepJnxSession session) throws Exception {
    session.validateServerAcl(ServerAclType.CREATE_DATABASE);
  }

  /** Database ACL checkbox Delete documents. Unchecked is {@link AclFlag#NODELETE}. */
  static void requireDeleteDocuments(KeepJnxSession session, Database db) throws Exception {
    if (!allowsDelete(flagsOf(session, db))) {
      deny("Delete documents");
    }
  }

  /**
   * Checkboxes for this design write. Empty when Designer access is enough on its own.
   */
  static void requireWritePrivileges(
      KeepJnxSession session, Database db, String type, String dxl) throws Exception {
    Set<AclFlag> required = requiredWriteFlags(type, dxl);
    if (required.isEmpty() || session == null) {
      return;
    }
    Set<AclFlag> flags = flagsOf(session, db);
    for (AclFlag flag : required) {
      if (!flags.contains(flag)) {
        deny(checkbox(flag));
      }
    }
  }

  static boolean allowsDelete(Set<AclFlag> flags) {
    return flags != null && !flags.contains(AclFlag.NODELETE);
  }

  static Set<AclFlag> requiredWriteFlags(String type, String dxl) {
    String t = type == null ? "" : DesignBulkTypes.normalize(type);
    boolean priv = isPrivate(dxl);
    EnumSet<AclFlag> required = EnumSet.noneOf(AclFlag.class);
    if ("view".equals(t) || "folder".equals(t)) {
      required.add(priv ? AclFlag.CREATE_PRFOLDER : AclFlag.CREATE_FOLDER);
    }
    if ("agent".equals(t)) {
      String kind = AgentKinds.detectOrDefault(dxl);
      if (AgentKinds.LOTUSSCRIPT.equals(kind)
          || AgentKinds.JAVA.equals(kind)
          || AgentKinds.IMPORTEDJAVA.equals(kind)) {
        required.add(AclFlag.CREATE_LOTUSSCRIPT);
      }
      if (priv) {
        required.add(AclFlag.CREATE_PRAGENT);
      }
    }
    return required;
  }

  static boolean isPrivate(String dxl) {
    return dxl != null && PRIVATE_ROOT.matcher(dxl).find();
  }

  private static Set<AclFlag> flagsOf(KeepJnxSession session, Database db) {
    AclAccess access =
        db.getACL()
            .lookupAccess(
                session.getClient().getEffectiveUserNamesList(session.getUserName()));
    Set<AclFlag> flags = access.getAclFlags();
    return flags == null ? Set.of() : flags;
  }

  private static void deny(String checkbox) throws KeepExceptionInsufficientAccess {
    throw new KeepExceptionInsufficientAccess(
        1024, checkbox, "not granted in the database ACL");
  }

  private static String checkbox(AclFlag flag) {
    return switch (flag) {
      case CREATE_LOTUSSCRIPT -> "Create LotusScript/Java agents";
      case CREATE_FOLDER -> "Create shared folders/views";
      case CREATE_PRFOLDER -> "Create personal folders/views";
      case CREATE_PRAGENT -> "Create private agents";
      default -> flag.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    };
  }
}
