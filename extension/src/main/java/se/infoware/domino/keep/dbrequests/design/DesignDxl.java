package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Validate design-element DXL and extract the design name from a required root element (e.g. {@code
 * page}, {@code outline}) or, for note-form exports, from {@code $TITLE}.
 */
final class DesignDxl {

  private DesignDxl() {}

  static String requireRootName(String dxl, String expectedRoot)
      throws KeepExceptionWrongDataRequest {
    return requireDesignName(dxl, expectedRoot);
  }

  /**
   * Validates that DXL has the expected root element (e.g. singleton About/Using documents). Does
   * not require a name attribute.
   */
  static void requireRootOnly(String dxl, String expectedRoot)
      throws KeepExceptionWrongDataRequest {
    parseAndRequireRoot(dxl, expectedRoot);
  }

  /**
   * Like {@link #requireRootOnly} but accepts several roots and returns the matched local name.
   */
  static String requireRootOnlyReturningLocalName(String dxl, String... expectedRoots)
      throws KeepExceptionWrongDataRequest {
    Element root = parseAndRequireRoot(dxl, expectedRoots);
    String localName = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
    return localName;
  }

  /**
   * Accepts any of the given root local names. Uses the {@code name} attribute when present;
   * otherwise reads item {@code $TITLE} (common for note-form design exports).
   */
  static String requireDesignName(String dxl, String... expectedRoots)
      throws KeepExceptionWrongDataRequest {
    Element root = parseAndRequireRoot(dxl, expectedRoots);
    String localName = root.getLocalName() != null ? root.getLocalName() : root.getTagName();

    String resolved = designNameFromRoot(root);
    if (resolved != null) {
      return resolved;
    }

    throw new KeepExceptionWrongDataRequest(
        localName.toLowerCase(Locale.ROOT)
            + " DXL requires a non-empty name attribute or $TITLE item");
  }

  /**
   * Shared Actions name: {@code name} attribute, {@code $TITLE}, or {@code $$ScriptName} (often
   * {@code $ACTIONS}).
   */
  static String requireSharedActionsName(String dxl) throws KeepExceptionWrongDataRequest {
    Element root = parseAndRequireRoot(dxl, "sharedactions");
    String resolved = designNameFromRoot(root);
    if (resolved != null) {
      return resolved;
    }
    String script = findItemText(root, "$$ScriptName");
    if (script != null && !script.isBlank()) {
      return script.trim();
    }
    throw new KeepExceptionWrongDataRequest(
        "sharedactions DXL requires a non-empty name attribute, $TITLE, or $$ScriptName item");
  }

  /**
   * Rewrite root {@code name} attribute and sync {@code $TITLE} / {@code $$ScriptName} text items
   * when present. Used when applying starter DXL to a caller-chosen name.
   */
  static String withName(String dxl, String name, String... expectedRoots)
      throws KeepExceptionWrongDataRequest {
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    String wanted = name.trim();
    Element root = parseAndRequireRoot(dxl, expectedRoots);
    Document doc = root.getOwnerDocument();
    root.setAttribute("name", wanted);
    setItemText(root, "$TITLE", wanted);
    setItemText(root, "$$ScriptName", wanted);
    try {
      return serializePreservingDoctype(doc, root, dxl);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionWrongDataRequest("cannot set design name: " + message);
    }
  }

  /**
   * Remove note identity ({@code <noteinfo>}, root {@code replicaid}) so a starter DXL creates a
   * new design note instead of UPSERT-replacing the source note by UNID.
   *
   * <p>String-based (no DOM round-trip) so binary items like {@code $FileData} stay intact.
   */
  static String forNewNote(String dxl) throws KeepExceptionWrongDataRequest {
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl body is required");
    }
    String out = dxl.replaceAll("(?is)<noteinfo\\b[^>]*>.*?</noteinfo>", "");
    out = out.replaceAll("(?i)\\s+replicaid=(['\"])[^'\"]*\\1", "");
    return out;
  }

  private static String serializePreservingDoctype(Document doc, Element root, String originalDxl)
      throws Exception {
    var transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.INDENT, "no");
    StringWriter sw = new StringWriter();
    transformer.transform(new DOMSource(doc), new StreamResult(sw));
    String out = sw.toString();
    if (!out.toLowerCase(Locale.ROOT).contains("<!doctype")
        && originalDxl.toLowerCase(Locale.ROOT).contains("<!doctype")) {
      String local = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
      String doctype = "<!DOCTYPE " + local + " SYSTEM 'xmlschemas/domino_14_5_1.dtd'>\n";
      int xmlEnd = out.indexOf("?>");
      if (xmlEnd >= 0) {
        out =
            out.substring(0, xmlEnd + 2)
                + "\n"
                + doctype
                + out.substring(xmlEnd + 2).stripLeading();
      } else {
        out = doctype + out;
      }
    }
    return out;
  }

  private static void setItemText(Element root, String itemName, String value) {
    NodeList items = root.getElementsByTagNameNS("*", "item");
    if (items.getLength() == 0) {
      items = root.getElementsByTagName("item");
    }
    for (int i = 0; i < items.getLength(); i++) {
      if (!(items.item(i) instanceof Element item)) {
        continue;
      }
      if (!itemName.equalsIgnoreCase(item.getAttribute("name"))) {
        continue;
      }
      NodeList texts = item.getElementsByTagNameNS("*", "text");
      if (texts.getLength() == 0) {
        texts = item.getElementsByTagName("text");
      }
      if (texts.getLength() > 0) {
        texts.item(0).setTextContent(value);
      }
    }
  }

  private static String designNameFromRoot(Element root) {
    String name = root.getAttribute("name");
    if (name != null && !name.isBlank()) {
      return name.trim();
    }
    String fromTitle = findItemText(root, "$TITLE");
    if (fromTitle != null && !fromTitle.isBlank()) {
      // Domino titles can be "Name|Alias" - use the first segment as design name.
      int pipe = fromTitle.indexOf('|');
      return (pipe >= 0 ? fromTitle.substring(0, pipe) : fromTitle).trim();
    }
    return null;
  }

  private static Element parseAndRequireRoot(String dxl, String... expectedRoots)
      throws KeepExceptionWrongDataRequest {
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl body is required");
    }
    if (expectedRoots == null || expectedRoots.length == 0) {
      throw new KeepExceptionWrongDataRequest("expected DXL root element is required");
    }

    Document doc;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setValidating(false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setExpandEntityReferences(false);
      doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(dxl)));
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionWrongDataRequest("invalid design DXL: " + message);
    }

    Element root = doc.getDocumentElement();
    if (root == null) {
      throw new KeepExceptionWrongDataRequest("invalid design DXL: missing root element");
    }

    String localName = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
    boolean ok =
        Arrays.stream(expectedRoots).anyMatch(r -> r != null && r.equalsIgnoreCase(localName));
    if (!ok) {
      String expected =
          Arrays.stream(expectedRoots)
              .map(r -> "<" + r + ">")
              .collect(Collectors.joining(" or "));
      throw new KeepExceptionWrongDataRequest(
          "DXL root element must be " + expected + ", got <" + localName + ">");
    }
    return root;
  }

  private static String findItemText(Element root, String itemName) {
    NodeList items = root.getElementsByTagNameNS("*", "item");
    if (items.getLength() == 0) {
      items = root.getElementsByTagName("item");
    }
    for (int i = 0; i < items.getLength(); i++) {
      Node node = items.item(i);
      if (!(node instanceof Element item)) {
        continue;
      }
      String n = item.getAttribute("name");
      if (!itemName.equalsIgnoreCase(n)) {
        continue;
      }
      NodeList texts = item.getElementsByTagNameNS("*", "text");
      if (texts.getLength() == 0) {
        texts = item.getElementsByTagName("text");
      }
      if (texts.getLength() > 0) {
        return texts.item(0).getTextContent();
      }
    }
    return null;
  }
}
