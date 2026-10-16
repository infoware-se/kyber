package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.exceptions.ErrorClassification;
import com.hcl.domino.keep.exceptions.KeepException;

/**
 * Database catalog category that marks an NSF as one Kyber may read or change. Domino stores it
 * in Database properties, on the Design tab, in Categories.
 */
final class DesignCatalog {

  static final String CATEGORY = "KyberEnabled";

  private DesignCatalog() {}

  static boolean has(Database db) {
    return db != null && contains(db.getCategories());
  }

  /** {@code true} when {@code categories} lists {@link #CATEGORY}, separated by comma or semicolon. */
  static boolean contains(String categories) {
    if (categories == null || categories.isBlank()) {
      return false;
    }
    for (String token : categories.split("[,;\\r\\n]+")) {
      if (CATEGORY.equalsIgnoreCase(token.trim())) {
        return true;
      }
    }
    return false;
  }

  static void require(Database db) throws KeepException {
    if (!has(db)) {
      throw KeepException.status(
          "Not Kyber enabled", ErrorClassification.FORBIDDEN);
    }
  }

  /** Adds {@link #CATEGORY} without dropping categories that are already stored. */
  static void mark(Database db) {
    if (db == null || has(db)) {
      return;
    }
    String current = db.getCategories();
    if (current == null || current.isBlank()) {
      db.setCategories(CATEGORY);
      return;
    }
    db.setCategories(current.trim() + ", " + CATEGORY);
  }
}
