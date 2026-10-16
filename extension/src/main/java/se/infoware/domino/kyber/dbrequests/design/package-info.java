/**
 * Design-element requests for the Kyber KEEP extension.
 *
 * <p>Each public class is a KEEP database request. {@code @MinimumAcl} is enforced by KEEP for
 * the authenticated user against the database that request opened. These calls do not run as the
 * Domino server id.
 *
 * <p>Listing or reading a design element needs Designer, the same level as creating or
 * replacing one. Replacing the
 * ACL needs Manager. Deleting a design note also needs the Delete documents checkbox, including
 * for a Manager. A shared view or folder, and a LotusScript or Java agent, need their own
 * checkbox for a Designer. Domino does not store those checkboxes on a Manager entry, because a
 * Manager cannot turn them off, so a Manager is treated as having them. Creating a new NSF needs
 * the server privilege Create databases &amp; templates. A blank field allows everyone. A listed
 * name may be a user, a server, or a group. A call acts only on a database whose catalog
 * category is KyberEnabled. Creating a database sets that category. Deleting an NSF needs Manager
 * on that file and the same category. {@code KeepConfig.nsf} cannot be deleted.
 *
 * <p>{@link ImportDesign} and {@link CreateDatabase} are the requests whose {@code @MinimumAcl}
 * applies to {@code KeepConfig.nsf}. KEEP opens that file so a missing target can be created. The
 * write itself is authorized on the caller's {@code nsfPath}. {@code KeepConfig.nsf} cannot be
 * created. {@link ListDatabases} lists the NotesDbDirectory file type this user can open. A file that cannot be opened is
 * omitted and {@code more} is set. The path is not returned.
 *
 * <p>{@link DesignElementExport} lists design notes. Data documents and agent run data stay in
 * the NSF. {@link DesignElementImport} removes the DXL {@code replicaid} attribute so the target
 * database keeps its own replica id.
 */
package se.infoware.domino.kyber.dbrequests.design;
