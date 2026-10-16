package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.keep.dbrequests.AsyncDominoJNXJson;
import com.hcl.domino.keep.dbrequests.KeepJnxSession;
import com.hcl.domino.keep.exceptions.KeepException;

/**
 * Design request that runs only against a database whose catalog category is {@code KyberEnabled}.
 * {@code KeepConfig.nsf} is the connection database when the target file may be missing, and is
 * not itself a Kyber database.
 */
abstract class KyberRequest extends AsyncDominoJNXJson {

  @Override
  public void accessControlCheck(KeepJnxSession session, Database db) throws KeepException {
    super.accessControlCheck(session, db);
    if (db == null) {
      return;
    }
    String path = db.getRelativeFilePath();
    if (path != null && ImportDesign.isKeepConfig(path)) {
      return;
    }
    DesignCatalog.require(db);
  }
}
