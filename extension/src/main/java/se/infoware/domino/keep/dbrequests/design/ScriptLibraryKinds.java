package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Script library language kinds: LotusScript, client JavaScript, Server JS (SSJS), Java.
 */
final class ScriptLibraryKinds {

  static final String LOTUSSCRIPT = "lotusscript";
  static final String JAVASCRIPT = "javascript";
  static final String SSJS = "ssjs";
  static final String JAVA = "java";

  static final String[] ALL = {LOTUSSCRIPT, JAVASCRIPT, SSJS, JAVA};

  private ScriptLibraryKinds() {}

  static String normalize(String kind) throws KeepExceptionWrongDataRequest {
    if (kind == null || kind.isBlank()) {
      return LOTUSSCRIPT;
    }
    String k = kind.trim().toLowerCase(Locale.ROOT);
    return switch (k) {
      case "lotusscript", "ls", "lotus" -> LOTUSSCRIPT;
      case "javascript", "js", "clientjs", "client" -> JAVASCRIPT;
      case "ssjs", "serverjs", "serverside", "server" -> SSJS;
      case "java" -> JAVA;
      default ->
          throw new KeepExceptionWrongDataRequest(
              "Unknown script library kind '"
                  + kind
                  + "'; expected lotusscript|javascript|ssjs|java");
    };
  }

  static boolean isKnown(String kind) {
    try {
      normalize(kind);
      return true;
    } catch (KeepExceptionWrongDataRequest e) {
      return false;
    }
  }

  /** Embedded resource path segment after {@code scriptlibrary} (empty for LotusScript). */
  static String embeddedSuffix(String kind) throws KeepExceptionWrongDataRequest {
    String k = normalize(kind);
    return switch (k) {
      case LOTUSSCRIPT -> "";
      case JAVASCRIPT -> "-javascript";
      case SSJS -> "-ssjs";
      case JAVA -> "-java";
      default -> "";
    };
  }

  /**
   * Detect language from DXL content. Returns {@code null} when markers are missing (do not treat
   * empty notes as LotusScript).
   */
  static String detect(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    String lower = dxl.toLowerCase(Locale.ROOT);
    if (lower.contains("<javaproject")) {
      return JAVA;
    }
    if (lower.contains("$serverjavascriptlibrary")
        || lower.contains("for='server'")
        || lower.contains("for=\"server\"")) {
      return SSJS;
    }
    if (lower.contains("<javascript")
        || lower.contains("for='web'")
        || lower.contains("for=\"web\"")) {
      return JAVASCRIPT;
    }
    if (lower.contains("<lotusscript")) {
      return LOTUSSCRIPT;
    }
    return null;
  }

  static String detectOrDefault(String dxl) {
    String d = detect(dxl);
    return d == null ? LOTUSSCRIPT : d;
  }

  /**
   * Set {@code name} on the root {@code scriptlibrary} element and sync {@code $TITLE} item text
   * when present.
   */
  static String withName(String dxl, String name) throws KeepExceptionWrongDataRequest {
    if (name == null || name.isBlank()) {
      throw new KeepExceptionWrongDataRequest("name is required");
    }
    String wanted = name.trim();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setValidating(false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setExpandEntityReferences(false);
      Document doc =
          factory.newDocumentBuilder().parse(new InputSource(new StringReader(dxl)));
      Element root = doc.getDocumentElement();
      if (root == null) {
        throw new KeepExceptionWrongDataRequest("invalid design DXL: missing root element");
      }
      String local = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
      if (!"scriptlibrary".equalsIgnoreCase(local)) {
        throw new KeepExceptionWrongDataRequest(
            "DXL root element must be <scriptlibrary>, got <" + local + ">");
      }
      root.setAttribute("name", wanted);

      NodeList items = root.getElementsByTagNameNS("*", "item");
      if (items.getLength() == 0) {
        items = root.getElementsByTagName("item");
      }
      for (int i = 0; i < items.getLength(); i++) {
        if (!(items.item(i) instanceof Element item)) {
          continue;
        }
        String itemName = item.getAttribute("name");
        if (!"$TITLE".equalsIgnoreCase(itemName) && !"TITLE".equalsIgnoreCase(itemName)) {
          continue;
        }
        NodeList texts = item.getElementsByTagNameNS("*", "text");
        if (texts.getLength() == 0) {
          texts = item.getElementsByTagName("text");
        }
        if (texts.getLength() > 0) {
          texts.item(0).setTextContent(wanted);
        }
      }

      // Keep java source file name aligned for Java starters
      NodeList javas = root.getElementsByTagNameNS("*", "java");
      if (javas.getLength() == 0) {
        javas = root.getElementsByTagName("java");
      }
      for (int i = 0; i < javas.getLength(); i++) {
        if (javas.item(i) instanceof Element javaEl) {
          String jn = javaEl.getAttribute("name");
          if (jn != null && jn.toLowerCase(Locale.ROOT).endsWith(".java")) {
            javaEl.setAttribute("name", wanted + ".java");
          }
        }
      }

      var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(OutputKeys.INDENT, "no");
      StringWriter sw = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(sw));
      String out = sw.toString();
      // Preserve DOCTYPE if transformer dropped it
      if (!out.toLowerCase(Locale.ROOT).contains("<!doctype")
          && dxl.toLowerCase(Locale.ROOT).contains("<!doctype")) {
        int xmlEnd = out.indexOf("?>");
        String doctype =
            "<!DOCTYPE scriptlibrary SYSTEM 'xmlschemas/domino_14_5_1.dtd'>\n";
        if (xmlEnd >= 0) {
          out = out.substring(0, xmlEnd + 2) + "\n" + doctype + out.substring(xmlEnd + 2).stripLeading();
        } else {
          out = doctype + out;
        }
      }
      return out;
    } catch (KeepExceptionWrongDataRequest e) {
      throw e;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionWrongDataRequest("cannot set script library name: " + message);
    }
  }

  static Set<String> allKinds() {
    return Set.of(ALL);
  }
}
