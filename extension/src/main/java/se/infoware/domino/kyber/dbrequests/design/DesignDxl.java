package se.infoware.domino.kyber.dbrequests.design;

import com.hcl.domino.keep.exceptions.KeepExceptionWrongDataRequest;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
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
   * A full ACL replace keeps {@code -Default-} and {@code LocalDomainServers}. Only {@code
   * aclentry} names count, so a log line that mentions either name does not. The level on an entry
   * may change.
   */
  static void requireProtectedAclEntries(String dxl) throws KeepExceptionWrongDataRequest {
    Element root = parseAndRequireRoot(dxl, "acl");
    NodeList entries = root.getElementsByTagNameNS("*", "aclentry");
    if (entries.getLength() == 0) {
      entries = root.getElementsByTagName("aclentry");
    }
    boolean hasDefault = false;
    boolean hasServers = false;
    for (int i = 0; i < entries.getLength(); i++) {
      Node node = entries.item(i);
      if (!(node instanceof Element entry)) {
        continue;
      }
      String name = entry.getAttribute("name");
      if (name == null) {
        continue;
      }
      String trimmed = name.trim();
      if ("-Default-".equalsIgnoreCase(trimmed)) {
        hasDefault = true;
      } else if ("LocalDomainServers".equalsIgnoreCase(trimmed)) {
        hasServers = true;
      }
    }
    if (hasDefault && hasServers) {
      return;
    }
    if (!hasDefault && !hasServers) {
      throw new KeepExceptionWrongDataRequest(
          "ACL entries -Default- and LocalDomainServers cannot be removed");
    }
    if (!hasDefault) {
      throw new KeepExceptionWrongDataRequest("ACL entry -Default- cannot be removed");
    }
    throw new KeepExceptionWrongDataRequest("ACL entry LocalDomainServers cannot be removed");
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
  /**
   * Design name from the {@code name} attribute or {@code $TITLE}. Null when the note has neither,
   * which is how Domino exports a system view such as {@code $FormulaClass}.
   */
  static String designName(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    try {
      return designNameFromRoot(parseRoot(dxl));
    } catch (Exception e) {
      return null;
    }
  }

  /** First element local name, ignoring the XML declaration and doctype. */
  static String rootLocalName(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(?is)<([A-Za-z][A-Za-z0-9_]*)\\b").matcher(dxl);
    return matcher.find() ? matcher.group(1) : null;
  }

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
    // Validate the root, then edit the original text. A DOM round-trip drops XPage class bytes.
    parseAndRequireRoot(dxl, expectedRoots);
    String out = replaceRootNameAttribute(dxl, wanted);
    out = replaceItemText(out, "$TITLE", wanted);
    out = replaceItemText(out, "$$ScriptName", wanted);
    return out;
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
    return withoutReplicaId(out);
  }

  /**
   * Drop the database {@code replicaid} attribute. The rest of the design note stays. Import uses
   * this so the target NSF keeps its own replica id.
   */
  static String withoutReplicaId(String dxl) {
    if (dxl == null || dxl.isBlank()) {
      return dxl;
    }
    return dxl.replaceAll("(?i)\\s+replicaid=(['\"])[^'\"]*\\1", "");
  }

  /**
   * Fill an empty {@code $ClassIndexItem} from the class file stored in each {@code $ClassData}
   * item, in item order. That order is how the XPages runtime maps the index onto {@code
   * $ClassData0}, {@code $ClassData1}, and so on. A list that already names classes is left as it
   * is.
   */
  static String withClassIndex(String dxl) {
    if (dxl == null || dxl.isBlank() || !dxl.contains("$ClassData")) {
      return dxl;
    }
    if (classIndexNamesClasses(dxl)) {
      return dxl;
    }
    List<String> classes = xspClassNames(dxl);
    if (classes.isEmpty()) {
      return dxl;
    }
    String item = classIndexItem(classes);
    Matcher empty =
        Pattern.compile(
                "(?s)<item\\s+name=(['\"])\\$ClassIndexItem\\1[^>]*>\\s*<textlist\\s*(?:/>|>\\s*</textlist>)\\s*</item>")
            .matcher(dxl);
    if (empty.find()) {
      return empty.replaceFirst(Matcher.quoteReplacement(item));
    }
    Matcher close = Pattern.compile("(?s)</([A-Za-z][\\w:.-]*)>\\s*$").matcher(dxl);
    if (!close.find()) {
      return dxl;
    }
    return close.replaceFirst(Matcher.quoteReplacement(item + "</" + close.group(1) + ">"));
  }

  private static boolean classIndexNamesClasses(String dxl) {
    Matcher item =
        Pattern.compile("(?s)<item\\s+name=(['\"])\\$ClassIndexItem\\1[^>]*>(.*?)</item>")
            .matcher(dxl);
    return item.find() && item.group(2).contains("<text>");
  }

  private static List<String> xspClassNames(String dxl) {
    List<String> classes = new ArrayList<>();
    Matcher data =
        Pattern.compile("(?s)<item\\s+name=(['\"])\\$ClassData\\d*\\1[^>]*>(.*?)</item>")
            .matcher(dxl);
    while (data.find()) {
      Matcher raw =
          Pattern.compile("(?s)<rawitemdata\\b[^>]*>(.*?)</rawitemdata>").matcher(data.group(2));
      if (!raw.find()) {
        continue;
      }
      byte[] bytes;
      try {
        bytes = Base64.getMimeDecoder().decode(raw.group(1).replaceAll("\\s", ""));
      } catch (IllegalArgumentException ex) {
        continue;
      }
      String defined = thisClassName(bytes);
      if (defined != null && defined.startsWith("xsp/")) {
        classes.add(defined);
        continue;
      }
      Matcher names =
          Pattern.compile("xsp/[A-Za-z_][A-Za-z0-9_$]*(?![A-Za-z0-9_$/])")
              .matcher(new String(bytes, StandardCharsets.ISO_8859_1));
      Set<String> found = new LinkedHashSet<>();
      while (names.find()) {
        found.add(names.group());
      }
      classes.addAll(found);
    }
    return classes;
  }

  /** The class file's {@code this_class}, after a Domino file header if one is present. */
  private static String thisClassName(byte[] bytes) {
    try {
      return definedClassName(bytes);
    } catch (IndexOutOfBoundsException ex) {
      return null;
    }
  }

  private static String definedClassName(byte[] bytes) {
    int magic = indexOf(bytes, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
    if (magic < 0 || magic + 10 > bytes.length) {
      return null;
    }
    int p = magic + 8;
    int count = u2(bytes, p);
    p += 2;
    if (count < 2) {
      return null;
    }
    String[] utf8 = new String[count];
    int[] classUtf8 = new int[count];
    for (int i = 1; i < count; i++) {
      if (p >= bytes.length) {
        return null;
      }
      int tag = bytes[p++] & 0xFF;
      switch (tag) {
        case 1 -> {
          if (p + 2 > bytes.length) {
            return null;
          }
          int len = u2(bytes, p);
          p += 2;
          if (len < 0 || p + len > bytes.length) {
            return null;
          }
          utf8[i] = new String(bytes, p, len, StandardCharsets.ISO_8859_1);
          p += len;
        }
        case 7, 8, 16, 19, 20 -> {
          if (p + 2 > bytes.length) {
            return null;
          }
          classUtf8[i] = u2(bytes, p);
          p += 2;
        }
        case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 4;
        case 5, 6 -> {
          p += 8;
          i++;
        }
        case 15 -> p += 3;
        default -> {
          return null;
        }
      }
    }
    if (p + 4 > bytes.length) {
      return null;
    }
    int thisClass = u2(bytes, p + 2);
    if (thisClass <= 0 || thisClass >= count) {
      return null;
    }
    int nameIndex = classUtf8[thisClass];
    if (nameIndex <= 0 || nameIndex >= count) {
      return null;
    }
    return utf8[nameIndex];
  }

  private static int u2(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
  }

  private static int indexOf(byte[] bytes, byte[] needle) {
    for (int i = 0; i + needle.length <= bytes.length; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (bytes[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }

  private static String classIndexItem(List<String> classes) {
    StringBuilder text = new StringBuilder();
    text.append("<item name='$ClassIndexItem'><textlist>");
    for (String cls : classes) {
      text.append("<text>WEB-INF/classes/").append(cls).append(".class</text>");
    }
    text.append("</textlist></item>");
    return text.toString();
  }

  private static String replaceRootNameAttribute(String dxl, String wanted) {
    Matcher root = Pattern.compile("(?s)<([A-Za-z][\\w:.-]*)\\b([^>]*)>").matcher(dxl);
    if (!root.find()) {
      return dxl;
    }
    String attrs = root.group(2);
    Matcher nameAttr = Pattern.compile("(?i)\\bname=(['\"])(.*?)\\1").matcher(attrs);
    String updated;
    if (nameAttr.find()) {
      String quote = nameAttr.group(1);
      updated =
          attrs.substring(0, nameAttr.start())
              + "name="
              + quote
              + xmlAttr(wanted, quote)
              + quote
              + attrs.substring(nameAttr.end());
    } else {
      updated = attrs + " name='" + xmlAttr(wanted, "'") + "'";
    }
    return dxl.substring(0, root.start())
        + "<"
        + root.group(1)
        + updated
        + ">"
        + dxl.substring(root.end());
  }

  private static String replaceItemText(String dxl, String itemName, String wanted) {
    Pattern item =
        Pattern.compile(
            "(?is)(<item\\s+name=(['\"])"
                + Pattern.quote(itemName)
                + "\\2[^>]*>\\s*<text>)[^<]*(</text>)");
    Matcher matcher = item.matcher(dxl);
    if (!matcher.find()) {
      return dxl;
    }
    return matcher.replaceFirst(
        Matcher.quoteReplacement(matcher.group(1) + wanted + matcher.group(3)));
  }

  private static String xmlAttr(String value, String quote) {
    String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    if ("'".equals(quote)) {
      return escaped.replace("'", "&apos;");
    }
    return escaped.replace("\"", "&quot;");
  }

  private static Element parseRoot(String dxl) throws Exception {
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl body is required");
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setValidating(false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setExpandEntityReferences(false);
    Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(dxl)));
    Element root = doc.getDocumentElement();
    if (root == null) {
      throw new KeepExceptionWrongDataRequest("invalid design DXL: missing root element");
    }
    return root;
  }

  private static String designNameFromRoot(Element root) {
    String name = root.getAttribute("name");
    if (name != null && !name.isBlank()) {
      return normalizeDesignName(root, name.trim());
    }
    String fromTitle = findItemText(root, "$TITLE");
    if (fromTitle != null && !fromTitle.isBlank()) {
      // Domino titles can be "Name|Alias" - use the first segment as design name.
      int pipe = fromTitle.indexOf('|');
      String title = (pipe >= 0 ? fromTitle.substring(0, pipe) : fromTitle).trim();
      return normalizeDesignName(root, title);
    }
    return null;
  }

  /**
   * Domino exports a shared agent as {@code name='(AgentName)'}. The list name has no parentheses,
   * and a round-trip PUT must accept that export.
   */
  private static String normalizeDesignName(Element root, String name) {
    String local = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
    if ("agent".equalsIgnoreCase(local)) {
      return unwrapAgentTitle(name);
    }
    return name;
  }

  /** Domino stores a run agent title as {@code (AgentName)}. The API name has no parentheses. */
  static String unwrapAgentTitle(String name) {
    if (name == null) {
      return null;
    }
    String trimmed = name.trim();
    if (trimmed.length() > 2
        && trimmed.charAt(0) == '('
        && trimmed.charAt(trimmed.length() - 1) == ')') {
      return trimmed.substring(1, trimmed.length() - 1).trim();
    }
    return trimmed;
  }

  private static Element parseAndRequireRoot(String dxl, String... expectedRoots)
      throws KeepExceptionWrongDataRequest {
    if (dxl == null || dxl.isBlank()) {
      throw new KeepExceptionWrongDataRequest("dxl body is required");
    }
    if (expectedRoots == null || expectedRoots.length == 0) {
      throw new KeepExceptionWrongDataRequest("expected DXL root element is required");
    }

    Element root;
    try {
      root = parseRoot(dxl);
    } catch (KeepExceptionWrongDataRequest e) {
      throw e;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new KeepExceptionWrongDataRequest("invalid design DXL: " + message);
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
