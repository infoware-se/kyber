package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.security.Acl;
import com.hcl.domino.security.AclEntry;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * After ACL DXL replace (full overwrite/drop) or NSF create: ensure LocalDomainAdmins /
 * LocalDomainServers are Manager (add or overwrite those two entries only).
 */
final class DesignAcl {

  private static final String LOCAL_DOMAIN_ADMINS = "LocalDomainAdmins";
  private static final String LOCAL_DOMAIN_SERVERS = "LocalDomainServers";

  private DesignAcl() {}

  static void ensureDomainManagers(Database db) {
    if (db == null) {
      return;
    }
    Acl acl = db.getACL();
    upsert(acl, LOCAL_DOMAIN_ADMINS, AclLevel.MANAGER, EnumSet.of(AclFlag.GROUP));
    upsert(acl, LOCAL_DOMAIN_SERVERS, AclLevel.MANAGER, EnumSet.of(AclFlag.SERVER));
    acl.save();
  }

  private static void upsert(
      Acl acl, String name, AclLevel level, Collection<AclFlag> flags) {
    Optional<AclEntry> existing = acl.getEntry(name);
    if (existing.isPresent()) {
      acl.updateEntry(name, name, level, List.of(), flags);
    } else {
      acl.addEntry(name, level, List.of(), flags);
    }
  }
}
