package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.ErrorClassification;
import com.hcl.domino.keep.exceptions.KeepException;
import com.hcl.domino.keep.exceptions.KeepExceptionInsufficientAccess;
import com.hcl.domino.security.AclAccess;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
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
 * checkboxes when Domino stores them. Delete documents can be off at every level.
 *
 * <p>Flags come from {@code Acl.lookupAccess} on the KEEP user's name list. The server id is not
 * consulted. A Designer level does not fill in a checkbox that is absent on that entry. Domino
 * does not store Create shared folders/views, Create personal folders/views, Create LotusScript/Java
 * agents, or Create private agents on a Manager entry, because a Manager cannot turn those boxes
 * off. A Manager is therefore treated as having them. Delete documents stays a stored flag at
 * every level, including Manager.
 */
final class DesignAccess {

  private static final Pattern PRIVATE_ROOT =
      Pattern.compile(
          "(?is)<(?:agent|view|folder)\\b[^>]*\\bprivate\\s*=\\s*['\"]true['\"]");

  private DesignAccess() {}

  /**
   * Server document privilege "Create databases &amp; templates", the same check HCL uses for
   * {@code POST /design/nsf}: {@link KeepJnxSession#validateServerAcl} with
   * {@link ServerAclType#CREATE_DATABASE}. That compares the field to the user's names list with
   * an exact string match. Off the server the check allows the create.
   */
  static void requireCreateDatabase(KeepJnxSession session) throws Exception {
    try {
      session.validateServerAcl(ServerAclType.CREATE_DATABASE);
    } catch (KeepExceptionInsufficientAccess denied) {
      throw KeepException.status(
          "Server privilege Create databases & templates is required",
          ErrorClassification.FORBIDDEN);
    }
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

  /** Stored ACL flags for the KEEP user, not for the server id. */
  private static Set<AclFlag> flagsOf(KeepJnxSession session, Database db) {
    AclAccess access =
        db.getACL()
            .lookupAccess(
                session.getClient().getEffectiveUserNamesList(session.getUserName()));
    return withManagerImpliedFlags(access.getAclLevel(), access.getAclFlags());
  }

  /**
   * Manager entries omit the design-creation checkboxes. Domino treats those boxes as on and
   * drops the attributes on import. Designer and below keep only the flags that are stored.
   */
  static Set<AclFlag> withManagerImpliedFlags(AclLevel level, Set<AclFlag> flags) {
    Set<AclFlag> stored = flags == null ? Set.of() : flags;
    if (level != AclLevel.MANAGER) {
      return stored;
    }
    EnumSet<AclFlag> implied = stored.isEmpty() ? EnumSet.noneOf(AclFlag.class) : EnumSet.copyOf(stored);
    implied.add(AclFlag.CREATE_FOLDER);
    implied.add(AclFlag.CREATE_PRFOLDER);
    implied.add(AclFlag.CREATE_LOTUSSCRIPT);
    implied.add(AclFlag.CREATE_PRAGENT);
    return implied;
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
