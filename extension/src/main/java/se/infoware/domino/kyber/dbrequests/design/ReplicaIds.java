package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import io.vertx.core.json.JsonObject;
import java.util.Locale;

/**
 * Replica id as Domino writes it in DXL and in an export folder name, for example {@code
 * 8525812300456789} in {@code kybertest.nsf-8525812300456789}.
 */
final class ReplicaIds {

  private ReplicaIds() {}

  static JsonObject put(JsonObject row, Database db) {
    String id = of(db);
    if (id != null) {
      row.put("replicaId", id);
    }
    return row;
  }

  static String of(Database db) {
    if (db == null) {
      return null;
    }
    try {
      return format(db.getReplicaID());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** {@code 85258123:00456789} becomes {@code 8525812300456789}. */
  static String format(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String id = raw.replace(":", "").trim().toUpperCase(Locale.ROOT);
    if (!id.matches("[0-9A-F]{16}")) {
      return null;
    }
    return id;
  }
}
