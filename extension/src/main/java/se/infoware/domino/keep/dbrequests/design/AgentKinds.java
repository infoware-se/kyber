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
 * Agent language/action kinds: LotusScript, Formula, Simple action, Java.
 *
 * <p>Imported Java is not a separate starter (rare; use raw DXL). Formula agents commonly use
 * {@code <simpleaction action='runformula'>}; plain simple actions use other {@code simpleaction}
 * values without formula.
 */
final class AgentKinds {

  static final String LOTUSSCRIPT = "lotusscript";
  static final String FORMULA = "formula";
  static final String SIMPLEACTION = "simpleaction";
  static final String JAVA = "java";

  static final String[] ALL = {LOTUSSCRIPT, FORMULA, SIMPLEACTION, JAVA};

  private AgentKinds() {}

  static String normalize(String kind) throws KeepExceptionWrongDataRequest {
    if (kind == null || kind.isBlank()) {
      return LOTUSSCRIPT;
    }
    String k = kind.trim().toLowerCase(Locale.ROOT);
    return switch (k) {
      case "lotusscript", "ls", "lotus" -> LOTUSSCRIPT;
      case "formula", "formulae", "@formula" -> FORMULA;
      case "simpleaction", "simple", "simpleactions", "actions" -> SIMPLEACTION;
      case "java" -> JAVA;
      default ->
          throw new KeepExceptionWrongDataRequest(
              "Unknown agent kind '"
                  + kind
                  + "'; expected lotusscript|formula|simpleaction|java");
    };
  }

  static String embeddedSuffix(String kind) throws KeepExceptionWrongDataRequest {
    String k = normalize(kind);
    return switch (k) {
      case LOTUSSCRIPT -> "";
      case FORMULA -> "-formula";
      case SIMPLEACTION -> "-simpleaction";
      case JAVA -> "-java";
      default -> "";
    };
  }

  /**
   * Detect agent kind from DXL. Returns {@code null} when markers are missing.
   *
   * <p>Priority: Java → LotusScript → Formula (incl. runformula) → other simpleaction.
   */
  static String detect(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    String lower = dxl.toLowerCase(Locale.ROOT);
    if (lower.contains("<javaproject")) {
      return JAVA;
    }
    if (lower.contains("<lotusscript")) {
      return LOTUSSCRIPT;
    }
    if (lower.contains("<formula") || lower.contains("action='runformula'")
        || lower.contains("action=\"runformula\"")) {
      return FORMULA;
    }
    if (lower.contains("<simpleaction")) {
      return SIMPLEACTION;
    }
    return null;
  }

  static String detectOrDefault(String dxl) {
    String d = detect(dxl);
    return d == null ? LOTUSSCRIPT : d;
  }

  static String defaultName(String kind) throws KeepExceptionWrongDataRequest {
    return switch (normalize(kind)) {
      case FORMULA -> "DefaultFormulaAgent";
      case SIMPLEACTION -> "DefaultSimpleAgent";
      case JAVA -> "DefaultJavaAgent";
      default -> "DefaultLotusScriptAgent";
    };
  }

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
      if (!"agent".equalsIgnoreCase(local)) {
        throw new KeepExceptionWrongDataRequest(
            "DXL root element must be <agent>, got <" + local + ">");
      }
      root.setAttribute("name", wanted);

      NodeList javas = root.getElementsByTagNameNS("*", "java");
      if (javas.getLength() == 0) {
        javas = root.getElementsByTagName("java");
      }
      for (int i = 0; i < javas.getLength(); i++) {
        if (javas.item(i) instanceof Element javaEl) {
          String jn = javaEl.getAttribute("name");
          if (jn != null && jn.toLowerCase(Locale.ROOT).endsWith(".java")) {
            // Keep Designer-style JavaAgent.java class file name for AgentBase agents
            if ("JavaAgent.java".equalsIgnoreCase(jn)) {
              continue;
            }
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
      if (!out.toLowerCase(Locale.ROOT).contains("<!doctype")
          && dxl.toLowerCase(Locale.ROOT).contains("<!doctype")) {
        int xmlEnd = out.indexOf("?>");
        String doctype = "<!DOCTYPE agent SYSTEM 'xmlschemas/domino_14_5_1.dtd'>\n";
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
    } catch (KeepExceptionWrongDataRequest e) {
      throw e;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionWrongDataRequest("cannot set agent name: " + message);
    }
  }

  static Set<String> allKinds() {
    return Set.of(ALL);
  }
}
