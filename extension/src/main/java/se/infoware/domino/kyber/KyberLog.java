package se.infoware.domino.kyber;

import com.hcl.domino.data.Database;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared JUL logging for Kyber. Use logger name {@code se.infoware.domino.kyber} (or a child) in
 * Domino/KEEP console filters.
 *
 * <p>{@link Level#INFO} — mutations (create/update/delete/import/ensure). {@link Level#WARNING} —
 * soft failures and repairs that operators should notice. {@link Level#FINE} — detail without
 * flooding the console on every read.
 */
public final class KyberLog {

  public static final String ROOT = "se.infoware.domino.kyber";

  private static final Logger LOG = Logger.getLogger(ROOT);

  private KyberLog() {}

  public static Logger of(Class<?> type) {
    return Logger.getLogger(type.getName());
  }

  public static String nsf(Database db) {
    if (db == null) {
      return "?";
    }
    try {
      String path = db.getRelativeFilePath();
      if (path == null || path.isBlank()) {
        path = db.getAbsoluteFilePath();
      }
      return path == null || path.isBlank() ? "?" : path.replace('\\', '/');
    } catch (Exception e) {
      return "?";
    }
  }

  public static void info(Logger log, String message) {
    if (log.isLoggable(Level.INFO)) {
      log.info(message);
    }
  }

  public static void warn(Logger log, String message) {
    if (log.isLoggable(Level.WARNING)) {
      log.warning(message);
    }
  }

  public static void fine(Logger log, String message) {
    if (log.isLoggable(Level.FINE)) {
      log.fine(message);
    }
  }

  public static void warn(Logger log, String message, Throwable error) {
    if (log.isLoggable(Level.WARNING)) {
      log.log(Level.WARNING, message, error);
    }
  }

  /** Root logger for one-off messages without a class logger. */
  public static Logger root() {
    return LOG;
  }
}
