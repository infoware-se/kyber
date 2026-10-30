package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Database;
import com.hcl.domino.security.Acl;
import com.hcl.domino.security.AclEntry;
import com.hcl.domino.security.AclFlag;
import com.hcl.domino.security.AclLevel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ACL entries applied when this API creates a database.
 *
 * <p>{@code kyber.nsf} gets the entries in {@link #createdAclEntries()}. Any other new database
 * gets the shape of {@code kyber_discussion145.nsf}, with the creating user and the server the
 * file is created on in place of the names stored in that database. An ACL imported later is left
 * as imported.
 */
final class DesignAcl {

  private static final String LOCAL_DOMAIN_ADMINS = "LocalDomainAdmins";
  private static final String LOCAL_DOMAIN_SERVERS = "LocalDomainServers";
  /** Designer shows this role as {@code [Configuration]}. */
  private static final String CONFIGURATION_ROLE = "Configuration";

  private DesignAcl() {}

  /**
   * Entries for a new database other than {@code kyber.nsf}. A blank creator or server is omitted.
   * When both names are the same, only the server entry is written.
   */
  static List<StarterEntry> applicationAclEntries(String creator, String server) {
    String person = blankToNull(creator);
    String host = blankToNull(server);
    if (person != null && host != null && person.equalsIgnoreCase(host)) {
      person = null;
    }
    List<StarterEntry> entries = new ArrayList<>();
    entries.add(entry("-Default-", AclLevel.AUTHOR, EnumSet.of(AclFlag.CREATE_PRFOLDER)));
    entries.add(entry("Anonymous", AclLevel.NOACCESS, EnumSet.noneOf(AclFlag.class)));
    entries.add(
        entry(
            "OtherDomainServers",
            AclLevel.NOACCESS,
            EnumSet.of(AclFlag.SERVER, AclFlag.GROUP)));
    if (host != null) {
      entries.add(entry(host, AclLevel.MANAGER, EnumSet.of(AclFlag.SERVER)));
    }
    if (person != null) {
      entries.add(entry(person, AclLevel.MANAGER, EnumSet.of(AclFlag.PERSON)));
    }
    entries.add(
        entry(
            LOCAL_DOMAIN_ADMINS,
            AclLevel.MANAGER,
            EnumSet.of(AclFlag.GROUP),
            List.of(CONFIGURATION_ROLE)));
    entries.add(
        entry(
            LOCAL_DOMAIN_SERVERS,
            AclLevel.MANAGER,
            EnumSet.of(AclFlag.SERVER, AclFlag.GROUP)));
    return List.copyOf(entries);
  }

  /** Entries written when this API creates {@code kyber.nsf}. */
  static List<StarterEntry> createdAclEntries() {
    return List.of(
        entry("-Default-", AclLevel.NOACCESS, EnumSet.noneOf(AclFlag.class)),
        entry("Anonymous", AclLevel.NOACCESS, EnumSet.noneOf(AclFlag.class)),
        entry(
            "OtherDomainServers",
            AclLevel.NOACCESS,
            EnumSet.of(AclFlag.SERVER, AclFlag.GROUP)),
        entry("LocalKeepAdmins", AclLevel.MANAGER, EnumSet.of(AclFlag.GROUP)),
        entry(
            LOCAL_DOMAIN_ADMINS,
            AclLevel.MANAGER,
            EnumSet.of(AclFlag.PERSON, AclFlag.GROUP)),
        entry(LOCAL_DOMAIN_SERVERS, AclLevel.MANAGER, EnumSet.of(AclFlag.SERVER)));
  }

  static void ensureApplicationAcl(Database db, String creator, String server) {
    if (db == null) {
      return;
    }
    Acl acl = db.getACL();
    acl.addRole(CONFIGURATION_ROLE);
    String host = blankToNull(server);
    if (host != null) {
      acl.setAdminServer(host);
    }
    for (StarterEntry entry : applicationAclEntries(creator, server)) {
      upsert(acl, entry);
    }
    acl.save();
  }

  static void ensureCreatedAcl(Database db) {
    if (db == null) {
      return;
    }
    Acl acl = db.getACL();
    for (StarterEntry entry : createdAclEntries()) {
      upsert(acl, entry);
    }
    acl.save();
  }

  /**
   * Structured ACL for {@code GET /acl}. The DXL remains the write format; this is read-only JSON
   * beside it. Each entry has {@code name}, {@code level}, {@code flags}, and {@code roles}.
   */
  static JsonObject readJson(Database db) {
    JsonObject out = new JsonObject();
    if (db == null) {
      out.put("entries", new JsonArray());
      out.put("roles", new JsonArray());
      return out;
    }
    Acl acl = db.getACL();
    String admin = acl.getAdminServer();
    if (admin != null && !admin.isBlank()) {
      out.put("adminServer", admin.trim());
    }
    JsonArray roles = new JsonArray();
    for (String role : acl.getRoles()) {
      if (role != null && !role.isBlank()) {
        roles.add(role);
      }
    }
    out.put("roles", roles);
    JsonArray entries = new JsonArray();
    for (AclEntry entry : acl.getEntries()) {
      if (entry == null || entry.getName() == null || entry.getName().isBlank()) {
        continue;
      }
      JsonObject row = new JsonObject().put("name", entry.getName());
      if (entry.getAclLevel() != null) {
        row.put("level", entry.getAclLevel().name().toLowerCase(Locale.ROOT));
      }
      JsonArray flags = new JsonArray();
      if (entry.getAclFlags() != null) {
        for (AclFlag flag :
            entry.getAclFlags().stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .collect(Collectors.toList())) {
          flags.add(flag.name().toLowerCase(Locale.ROOT));
        }
      }
      row.put("flags", flags);
      JsonArray entryRoles = new JsonArray();
      if (entry.getRoles() != null) {
        for (String role : entry.getRoles()) {
          if (role != null && !role.isBlank()) {
            entryRoles.add(role);
          }
        }
      }
      row.put("roles", entryRoles);
      entries.add(row);
    }
    out.put("entries", entries);
    return out;
  }

  private static StarterEntry entry(String name, AclLevel level, Collection<AclFlag> flags) {
    return entry(name, level, flags, List.of());
  }

  private static StarterEntry entry(
      String name, AclLevel level, Collection<AclFlag> flags, List<String> roles) {
    return new StarterEntry(name, level, flags, roles);
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  record StarterEntry(
      String name, AclLevel level, Collection<AclFlag> flags, List<String> roles) {}

  private static void upsert(Acl acl, StarterEntry entry) {
    List<String> roles = entry.roles() == null ? List.of() : entry.roles();
    Optional<AclEntry> existing = acl.getEntry(entry.name());
    if (existing.isPresent()) {
      acl.updateEntry(entry.name(), entry.name(), entry.level(), roles, entry.flags());
    } else {
      acl.addEntry(entry.name(), entry.level(), roles, entry.flags());
    }
  }
}
