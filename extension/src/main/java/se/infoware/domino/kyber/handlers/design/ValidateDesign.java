package se.infoware.domino.kyber.handlers.design;

import com.hcl.domino.keep.handlers.core.DefaultJsonHandler;

/**
 * POST /validate — dry run of a design import. Pre-checks plus Domino importer against a scratch
 * NSF; the target database is not written.
 */
public class ValidateDesign extends DefaultJsonHandler {}
