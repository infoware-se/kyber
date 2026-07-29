package se.infoware.domino.keep.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Domino Designer Code &gt; Java kinds: class, interface, enum ({@code $Flags} like {@code
 * 34567Cg~[}).
 */
final class JavaSourceKinds {

  static final String CLASS = "class";
  static final String INTERFACE = "interface";
  static final String ENUM = "enum";

  static final String[] ALL = {CLASS, INTERFACE, ENUM};

  private static final Pattern TITLE =
      Pattern.compile("\\$TITLE['\"]?[^>]*>\\s*<text>([^<]+)</text>", Pattern.CASE_INSENSITIVE);
  private static final Pattern RAW =
      Pattern.compile(
          "<rawitemdata[^>]*>\\s*([A-Za-z0-9+/=\\s]+)\\s*</rawitemdata>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private JavaSourceKinds() {}

  static String normalize(String kind) throws KeepExceptionWrongDataRequest {
    if (kind == null || kind.isBlank()) {
      return CLASS;
    }
    String k = kind.trim().toLowerCase(Locale.ROOT);
    return switch (k) {
      case "class", "java" -> CLASS;
      case "interface", "iface" -> INTERFACE;
      case "enum", "enumeration" -> ENUM;
      default ->
          throw new KeepExceptionWrongDataRequest(
              "Unknown javasource kind '" + kind + "'; expected class|interface|enum");
    };
  }

  static String embeddedSuffix(String kind) throws KeepExceptionWrongDataRequest {
    return switch (normalize(kind)) {
      case INTERFACE -> "-interface";
      case ENUM -> "-enum";
      default -> "";
    };
  }

  static String defaultName(String kind) throws KeepExceptionWrongDataRequest {
    return switch (normalize(kind)) {
      case INTERFACE -> "se/infoware/keep/DefaultHelloInterface.java";
      case ENUM -> "se/infoware/keep/DefaultHelloEnum.java";
      default -> "se/infoware/keep/DefaultHello.java";
    };
  }

  static String detect(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    String body = filePayload(dxl);
    if (body != null) {
      String compact = body.replace('\r', ' ');
      if (compact.contains("public interface ")
          || compact.contains("\ninterface ")
          || compact.startsWith("interface ")) {
        return INTERFACE;
      }
      if (compact.contains("public enum ")
          || compact.contains("\nenum ")
          || compact.startsWith("enum ")) {
        return ENUM;
      }
      if (compact.contains("public class ")
          || compact.contains("\nclass ")
          || compact.startsWith("class ")) {
        return CLASS;
      }
    }
    Matcher tm = TITLE.matcher(dxl);
    if (tm.find()) {
      String title = tm.group(1).toLowerCase(Locale.ROOT);
      if (title.contains("interface")) {
        return INTERFACE;
      }
      if (title.contains("enum")) {
        return ENUM;
      }
      if (title.endsWith(".java")) {
        return CLASS;
      }
    }
    return null;
  }

  static String detectOrDefault(String dxl) {
    String d = detect(dxl);
    return d == null ? CLASS : d;
  }

  /** Set {@code $TITLE} / {@code $FileNames} on a javasource {@code <note>} DXL. */
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
      NodeList items = root.getElementsByTagNameNS("*", "item");
      if (items.getLength() == 0) {
        items = root.getElementsByTagName("item");
      }
      for (int i = 0; i < items.getLength(); i++) {
        if (!(items.item(i) instanceof Element item)) {
          continue;
        }
        String itemName = item.getAttribute("name");
        if (!"$TITLE".equalsIgnoreCase(itemName)
            && !"TITLE".equalsIgnoreCase(itemName)
            && !"$FileNames".equalsIgnoreCase(itemName)
            && !"FileNames".equalsIgnoreCase(itemName)) {
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
        String doctype = "<!DOCTYPE note SYSTEM 'xmlschemas/domino_14_5_1.dtd'>\n";
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
      throw new KeepExceptionWrongDataRequest("cannot set javasource name: " + message);
    }
  }

  private static String filePayload(String dxl) {
    Matcher m = RAW.matcher(dxl);
    if (!m.find()) {
      return null;
    }
    try {
      byte[] raw = Base64.getDecoder().decode(m.group(1).replaceAll("\\s+", ""));
      if (raw.length <= 42) {
        return null;
      }
      int end = raw.length;
      if (raw[end - 1] == 0) {
        end--;
      }
      return new String(raw, 42, end - 42, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }
}
