package de.fjordkommission.utmbackup.provider.utm;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the native UTM identity from a package's {@code config.plist}.
 *
 * <p>The {@code UUID} key inside the root {@code Information} dictionary is the stable
 * identity of a UTM VM. Package names
 * are not, because the user can rename a VM, so a rename never changes this value. Only
 * XML property lists are read. A binary or otherwise unreadable file yields empty, and the
 * caller must then keep the existing identity rather than guess one.
 */
public final class UtmPackageIdentity {

    static final String CONFIG_FILE = "config.plist";

    /** UTM keeps the VM identity in the {@code Information} dictionary of the root. */
    static final String INFORMATION_KEY = "Information";
    static final String UUID_KEY = "UUID";

    /** Larger files are not configuration files, so they are not parsed. */
    private static final long MAX_CONFIG_BYTES = 4L * 1024 * 1024;

    private static final Pattern UUID = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");

    private UtmPackageIdentity() {
    }

    /**
     * Returns the upper-case UUID of a {@code .utm} package, or empty when there is no
     * readable, well-formed {@code UUID} entry.
     */
    public static Optional<String> readPackageUuid(Path packageDirectory) {
        Path config = packageDirectory.resolve(CONFIG_FILE);

        if (!Files.isRegularFile(config)) {
            return Optional.empty();
        }

        try {
            if (Files.size(config) > MAX_CONFIG_BYTES) {
                return Optional.empty();
            }

            return stringAt(config, INFORMATION_KEY, UUID_KEY)
                    .map(String::trim)
                    .filter(value -> UUID.matcher(value).matches())
                    .map(value -> value.toUpperCase(Locale.ROOT));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the UUID of the package(s) inside a backup container. A container without a
     * readable UUID, or with packages that disagree, yields empty so that the historical
     * identity cannot be guessed.
     */
    public static Optional<String> readContainerUuid(Path container) {
        if (!Files.isDirectory(container)) {
            return Optional.empty();
        }

        List<String> packages;

        try (Stream<Path> children = Files.list(container)) {
            packages = children
                    .filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve(CONFIG_FILE)))
                    .map(UtmPackageIdentity::readPackageUuid)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }

        Set<String> distinct = new HashSet<>(packages);

        return distinct.size() == 1 ? Optional.of(distinct.iterator().next()) : Optional.empty();
    }

    /**
     * Returns the string at a key path below the root dictionary. Every step must be a
     * dictionary key whose value is either the next dictionary or, for the last step, a
     * string. Any other shape yields empty.
     */
    private static Optional<String> stringAt(Path config, String... keyPath) throws IOException {
        Document document;

        try {
            // Apple's DOCTYPE is accepted, but the external DTD is never fetched and
            // external entities are never resolved, so the file cannot reach the network or disk.
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(config.toFile());
        } catch (SAXException | ParserConfigurationException e) {
            return Optional.empty();
        }

        Element root = document.getDocumentElement();

        if (root == null || !"plist".equals(root.getTagName())) {
            return Optional.empty();
        }

        Element current = firstChildElement(root, "dict");

        for (int step = 0; step < keyPath.length && current != null; step++) {
            Element value = valueOf(current, keyPath[step]);
            boolean last = step == keyPath.length - 1;

            if (value == null) {
                return Optional.empty();
            }

            if (last) {
                return "string".equals(value.getTagName())
                        ? Optional.of(value.getTextContent())
                        : Optional.empty();
            }

            current = "dict".equals(value.getTagName()) ? value : null;
        }

        return Optional.empty();
    }

    /** The value element that follows {@code key} in a dictionary, or null. */
    private static Element valueOf(Element dictionary, String key) {
        // A plist dictionary alternates key and value elements.
        for (Node node = dictionary.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "key".equals(element.getTagName())
                    && key.equals(element.getTextContent())) {
                return nextElement(element);
            }
        }

        return null;
    }

    private static Element firstChildElement(Element parent, String tagName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }

        return null;
    }

    private static Element nextElement(Element element) {
        for (Node node = element.getNextSibling(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element next) {
                return next;
            }
        }

        return null;
    }
}
