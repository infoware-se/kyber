package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoClientBuilder;
import com.hcl.domino.data.Database;
import com.hcl.domino.data.DominoCollection;
import com.hcl.domino.data.Find;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.ErrorClassification;
import com.hcl.domino.keep.exceptions.KeepException;
import com.hcl.domino.keep.exceptions.KeepExceptionInsufficientAccess;
import com.hcl.domino.security.AclAccess;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
import com.hcl.domino.security.ServerAclType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
   * Server document privilege "Create databases &amp; templates".
   *
   * <p>A blank value allows everyone. A filled value may list users, servers, and groups, a
   * certifier wildcard (an asterisk, a slash, and the certifier, as in Sales/East/Renovations), or
   * a directory view (an asterisk and the view name, as in ($Users)). The signed-in user's names
   * list includes the groups that user belongs to. Any one match allows the create. Several names
   * may be separated by commas or semicolons. Off the server the check allows the create.
   */
  static void requireCreateDatabase(KeepJnxSession session) throws Exception {
    List<String> field = readCreateAccess();
    if (field == null) {
      denyUnlessKeepAllows(session);
      return;
    }
    List<String> userNames = effectiveNames(session);
    if (createAccessAllows(field, userNames, view -> listedInDirectoryView(view, userNames))) {
      return;
    }
    throw KeepException.status(
        "Server privilege Create databases & templates is required",
        ErrorClassification.FORBIDDEN);
  }

  /**
   * {@code true} when Create databases &amp; templates lists nobody. Domino then allows every
   * user. A blank entry is the same as an empty list. {@code null} means the same for this
   * helper; a failed server read is handled by the caller.
   */
  static boolean createAccessAllowsEveryone(List<String> names) {
    if (names == null || names.isEmpty()) {
      return true;
    }
    for (String name : names) {
      if (name != null && !name.isBlank()) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code true} when {@code field} is blank or one entry matches {@code userNames}. A view entry
   * does not match here.
   */
  static boolean createAccessAllows(List<String> field, List<String> userNames) {
    return createAccessAllows(field, userNames, view -> false);
  }

  /**
   * {@code true} when {@code field} is blank or one entry matches. {@code inDirectoryView} receives
   * the view name from an asterisk-prefixed entry such as ($Users).
   */
  static boolean createAccessAllows(
      List<String> field, List<String> userNames, Predicate<String> inDirectoryView) {
    if (createAccessAllowsEveryone(field)) {
      return true;
    }
    List<String> names = userNames == null ? List.of() : userNames;
    Predicate<String> inView = inDirectoryView == null ? view -> false : inDirectoryView;
    for (String entry : entriesOf(field)) {
      if (matchesEntry(entry, names, inView)) {
        return true;
      }
    }
    return false;
  }

  /** {@code null} when the server item cannot be read. The caller then uses KEEP's check. */
  private static List<String> readCreateAccess() {
    DominoClient client = null;
    try {
      client = DominoClientBuilder.newDominoClient().asIDUser().build();
      if (!client.isOnServer()) {
        return List.of();
      }
      String server = client.getIDUserName();
      return client.getServerInfo(server, server).getAclInfo(ServerAclType.CREATE_DATABASE);
    } catch (IllegalStateException missing) {
      return List.of();
    } catch (RuntimeException failed) {
      return null;
    } finally {
      if (client != null) {
        try {
          client.close();
        } catch (RuntimeException ignored) {
          // The privilege check still runs.
        }
      }
    }
  }

  private static void denyUnlessKeepAllows(KeepJnxSession session) throws Exception {
    try {
      session.validateServerAcl(ServerAclType.CREATE_DATABASE);
    } catch (KeepExceptionInsufficientAccess denied) {
      throw KeepException.status(
          "Server privilege Create databases & templates is required",
          ErrorClassification.FORBIDDEN);
    }
  }

  private static List<String> effectiveNames(KeepJnxSession session) {
    if (session == null) {
      return List.of();
    }
    try {
      var names = session.getClient().getEffectiveUserNamesList(session.getUserName());
      if (names != null && names.toList() != null && !names.toList().isEmpty()) {
        List<String> list = new ArrayList<>(names.toList());
        String user = session.getUserName();
        if (user != null && !user.isBlank()) {
          list.add(user);
        }
        return list;
      }
    } catch (RuntimeException ignored) {
      // The session user is still compared.
    }
    String user = session.getUserName();
    return user == null || user.isBlank() ? List.of() : List.of(user);
  }

  private static List<String> entriesOf(List<String> field) {
    List<String> entries = new ArrayList<>();
    for (String item : field) {
      if (item == null) {
        continue;
      }
      for (String part : item.split("[,;]")) {
        String token = part.trim();
        if (!token.isEmpty()) {
          entries.add(token);
        }
      }
    }
    return entries;
  }

  private static boolean matchesEntry(
      String entry, List<String> userNames, Predicate<String> inDirectoryView) {
    if (entry.startsWith("*/")) {
      for (String name : userNames) {
        if (name != null && certifiedBy(entry, name)) {
          return true;
        }
      }
      return false;
    }
    if (entry.startsWith("*")) {
      String view = entry.substring(1).trim();
      return !view.isEmpty() && inDirectoryView.test(view);
    }
    for (String name : userNames) {
      if (name != null && sameIdentity(entry, name)) {
        return true;
      }
    }
    return false;
  }

  /** A certifier wildcard matches a hierarchical name that ends with that certifier. */
  private static boolean certifiedBy(String pattern, String userName) {
    if (userName.startsWith("*")) {
      return false;
    }
    List<String> suffix = components(pattern.substring(2));
    List<String> user = components(userName);
    if (suffix.isEmpty() || user.size() <= suffix.size()) {
      return false;
    }
    int offset = user.size() - suffix.size();
    for (int i = 0; i < suffix.size(); i++) {
      if (!user.get(offset + i).equalsIgnoreCase(suffix.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameIdentity(String left, String right) {
    if (left.startsWith("*") || right.startsWith("*")) {
      return false;
    }
    List<String> a = components(left);
    List<String> b = components(right);
    if (a.isEmpty() || a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).equalsIgnoreCase(b.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static List<String> components(String name) {
    List<String> parts = new ArrayList<>();
    for (String raw : name.split("/")) {
      String piece = raw.trim();
      if (piece.isEmpty()) {
        continue;
      }
      int eq = piece.indexOf('=');
      if (eq > 0 && isNameLabel(piece.substring(0, eq))) {
        piece = piece.substring(eq + 1).trim();
      }
      if (!piece.isEmpty()) {
        parts.add(piece);
      }
    }
    return parts;
  }

  private static boolean isNameLabel(String label) {
    return label.equalsIgnoreCase("CN")
        || label.equalsIgnoreCase("OU")
        || label.equalsIgnoreCase("O")
        || label.equalsIgnoreCase("C");
  }

  /**
   * {@code *($Users)} means every name in that view of the first Domino directory. A lookup
   * failure does not grant the privilege.
   */
  private static boolean listedInDirectoryView(String viewName, List<String> userNames) {
    if (viewName == null || viewName.isBlank() || userNames == null || userNames.isEmpty()) {
      return false;
    }
    DominoClient client = null;
    try {
      client = DominoClientBuilder.newDominoClient().asIDUser().build();
      if (!client.isOnServer()) {
        return false;
      }
      Database directory = client.openDatabase("names.nsf");
      Optional<DominoCollection> view = directory.openCollection(viewName);
      if (view.isEmpty()) {
        return false;
      }
      DominoCollection collection = view.get();
      for (String name : userNames) {
        if (name == null || name.isBlank() || name.startsWith("*")) {
          continue;
        }
        if (viewContains(collection, name)) {
          return true;
        }
        String abbreviated = String.join("/", components(name));
        if (!abbreviated.equalsIgnoreCase(name) && viewContains(collection, abbreviated)) {
          return true;
        }
      }
      return false;
    } catch (RuntimeException failed) {
      return false;
    } finally {
      if (client != null) {
        try {
          client.close();
        } catch (RuntimeException ignored) {
          // The privilege check still runs.
        }
      }
    }
  }

  private static boolean viewContains(DominoCollection collection, String key) {
    Set<Integer> hits =
        collection.getAllIdsByKey(EnumSet.of(Find.EQUAL, Find.CASE_INSENSITIVE), key);
    return hits != null && !hits.isEmpty();
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
