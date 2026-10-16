package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.data.Document;
import com.hcl.domino.design.DesignElement;

/**
 * Comment stored after a design import.
 *
 * <p>A {@code comment} attribute in the DXL is left as imported. Otherwise the comment from before
 * the import is restored. There is no separate comment field on the write body.
 */
final class CommentUpdate {

  private final String previous;

  private CommentUpdate(String previous) {
    this.previous = previous;
  }

  static CommentUpdate preserve(String previous) {
    return new CommentUpdate(previous);
  }

  Decision decision(String dxl) {
    if (DesignComment.commentAttribute(dxl) != null) {
      return new Decision(false, null);
    }
    if (previous == null) {
      return new Decision(false, null);
    }
    return new Decision(true, previous);
  }

  void apply(DesignElement element, String dxl) {
    if (element == null) {
      return;
    }
    Decision decision = decision(dxl);
    if (decision.change()) {
      element.setComment(decision.value());
    }
  }

  void apply(Document document, String dxl) {
    if (document == null) {
      return;
    }
    Decision decision = decision(dxl);
    if (decision.change()) {
      document.replaceItemValue("$Comment", decision.value());
    }
  }

  record Decision(boolean change, String value) {}
}
