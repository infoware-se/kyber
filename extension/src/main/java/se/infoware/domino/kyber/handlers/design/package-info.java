/**
 * HTTP entry points for Kyber design routes.
 *
 * <p>Create and update handlers extend {@link AbstractImportDxlHandler}. They place the raw DXL
 * body on the KEEP payload, the same way HCL {@code UpdateDxl} does. They do not decide who may
 * write. The matching class in {@code se.infoware.domino.kyber.dbrequests.design} opens the
 * target NSF and applies that user's ACL.
 *
 * <p>{@link ImportDesign} and {@link CreateDatabase} save the caller's {@code nsfPath} and point
 * the KEEP connection at {@code KeepConfig.nsf}. That remap exists so a missing NSF can be created.
 * It does not grant access to the target database. {@link ListDatabases} uses {@code
 * KeepConfig.nsf} only when the caller omits {@code nsfPath}. That does not grant access to any
 * other database.
 */
package se.infoware.domino.kyber.handlers.design;
