package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.DominoClient;
import com.hcl.domino.admin.replication.ReplicaInfo;
import com.hcl.domino.admin.replication.ReplicationFlags;
import com.hcl.domino.data.Database;
import com.hcl.domino.dbdirectory.DirectorySearchQuery;
import com.hcl.domino.dbdirectory.FileType;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.DbRequestParameters;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import com.hcl.domino.keep.security.acl.MinimumAcl;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * List databases this user can open. GET /databases?fileType=
 *
 * <p>Same file types as LotusScript {@code NotesDbDirectory.GetFirstDatabase}. {@code database}
 * (1247, COM {@code NOTES_DATABASE}) is an NSF, NSG, or NSH file and is the default. {@code
 * template} (1248) is an NTF file. {@code replica_candidate} (1245) is a database or template that
 * is not disabled for replication. {@code template_candidate} (1246) is any database or template.
 *
 * <p>The directory scan is not an ACL check. A path is returned only after this user opens that
 * file. {@code ALLPRIVS} and {@code NOPRIVCHECK} are not set. A file that cannot be opened is
 * left out, {@code more} is true, and the path is not returned.
 */
@MinimumAcl(AclLevel.READER)
public class ListDatabases extends KyberRequest {

 /** One {@code NotesDbDirectory.GetFirstDatabase} file type. */
  enum DirectoryFileType {
    DATABASE("database", 1247),
    TEMPLATE("template", 1248),
    REPLICA_CANDIDATE("replica_candidate", 1245),
    TEMPLATE_CANDIDATE("template_candidate", 1246);

    private final String wire;
    private final int code;

    DirectoryFileType(String wire, int code) {
      this.wire = wire;
      this.code = code;
    }

    String wire() {
      return wire;
    }

    int code() {
      return code;
    }
  }

  @Override
  public void process(DbRequestParameters<JsonObject> request) throws Exception {
    DirectoryFileType fileType = fileTypeOf(request.incoming.getString("fileType"));
    KeepJnxSession session = request.session();
    DominoClient client = session.getClient();
    Set<String> paths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    DirectorySearchQuery query =
        client
            .openDbDirectory()
            .query()
            .withServer("")
            .withDirectory("")
            .withFlags(
                EnumSet.of(
                    DirectorySearchQuery.SearchFlag.FILETYPE,
                    DirectorySearchQuery.SearchFlag.SUMMARY,
                    DirectorySearchQuery.SearchFlag.SESSION_USERNAME))
            // ANY, then keep the extensions for this file type. DBANY does not return templates.
            .withFileTypes(
                EnumSet.of(FileType.ANY, FileType.DIRS, FileType.RECURSE, FileType.NOUPDIRS));
    query.forEach(
        0,
        -1,
        (entry, loop) -> {
          String path = dataPath(entry.getFilePath());
          if (path != null && matchesFileType(path, fileType)) {
            paths.add(path);
          }
        });

    boolean more = false;
    List<JsonObject> listed = new ArrayList<>();
    for (String path : paths) {
      Database db = null;
      try {
        String[] where = ImportDesign.serverAndPath(path);
        db = client.openDatabase(where[0], where[1]);
        if (!includeOpened(fileType, replicationDisabled(db)) || !DesignCatalog.has(db)) {
          continue;
        }
        JsonObject row = ReplicaIds.put(new JsonObject().put("nsfPath", path), db);
        String title = db.getTitle();
        if (title != null && !title.isBlank()) {
          row.put("dbTitle", title.trim());
        }
        listed.add(row);
      } catch (Exception openFailed) {
        if (!ImportDesign.isMissingDatabase(openFailed)) {
          more = true;
        }
      } finally {
        if (db != null) {
          db.close();
        }
      }
    }
    listed.sort(Comparator.comparing(row -> row.getString("nsfPath"), String.CASE_INSENSITIVE_ORDER));

    JsonArray databases = new JsonArray();
    for (JsonObject row : listed) {
      databases.add(row);
    }
    request.emit(
        new JsonObject()
            .put("fileType", fileType.wire())
            .put("databases", databases)
            .put("more", more));
  }

  /**
   * Blank means {@code database} (1247). Names are the LotusScript constants. {@code
   * notes_database} is the COM name for 1247. The integers are accepted too.
   */
  static DirectoryFileType fileTypeOf(String raw) throws KeepExceptionWrongDataRequest {
    if (raw == null || raw.isBlank()) {
      return DirectoryFileType.DATABASE;
    }
    String token = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    for (DirectoryFileType type : DirectoryFileType.values()) {
      if (token.equals(type.wire()) || token.equals(Integer.toString(type.code()))) {
        return type;
      }
    }
    if ("notes_database".equals(token)) {
      return DirectoryFileType.DATABASE;
    }
    throw new KeepExceptionWrongDataRequest(
        "fileType must be database (1247), template (1248), replica_candidate (1245),"
            + " or template_candidate (1246)");
  }

  /** A replica candidate that is disabled for replication is not of that file type. */
  static boolean includeOpened(DirectoryFileType fileType, boolean replicationDisabled) {
    return fileType != DirectoryFileType.REPLICA_CANDIDATE || !replicationDisabled;
  }

  private static boolean replicationDisabled(Database db) {
    try {
      ReplicaInfo info = db.getAdapter(ReplicaInfo.class);
      if (info == null) {
        return false;
      }
      return info.isReplicationFlagSet(ReplicationFlags.DISABLE);
    } catch (RuntimeException failed) {
      return false;
    }
  }

  /**
   * Data-directory path with forward slashes. An absolute path is dropped so a physical path is
   * not returned.
   */
  static String dataPath(String raw) {
    if (raw == null) {
      return null;
    }
    String path = raw.trim().replace('\\', '/');
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (path.isEmpty() || path.indexOf(':') >= 0 || path.contains("//")) {
      return null;
    }
    return path;
  }

  static boolean matchesFileType(String path, DirectoryFileType fileType) {
    String lower = path.toLowerCase(Locale.ROOT);
    int dot = lower.lastIndexOf('.');
    if (dot < 0 || dot == lower.length() - 1) {
      return false;
    }
    String extension = lower.substring(dot + 1);
    boolean database = extension.equals("nsf") || extension.equals("nsg") || extension.equals("nsh");
    boolean template = extension.equals("ntf");
    return switch (fileType) {
      case DATABASE -> database;
      case TEMPLATE -> template;
      case REPLICA_CANDIDATE, TEMPLATE_CANDIDATE -> database || template;
    };
  }
}
