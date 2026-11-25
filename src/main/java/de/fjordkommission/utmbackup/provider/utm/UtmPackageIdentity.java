package de.fjordkommission.utmbackup.provider.utm;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
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
    static final String ICON_KEY = "Icon";
    static final String ICON_CUSTOM_KEY = "IconCustom";
    /** UTM keeps a custom icon image in the {@code Data} folder of the package. */
    static final String ICON_DIRECTORY = "Data";

    /** A custom icon is a small image. Larger files are ignored rather than served. */
    private static final long MAX_ICON_BYTES = 2L * 1024 * 1024;

    /** Larger files are not configuration files, so they are not parsed. */
    private static final long MAX_CONFIG_BYTES = 4L * 1024 * 1024;

    private static final Pattern UUID = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");

    /** Fails the parse on error or fatal error, and ignores warnings. Prints nothing. */
    private static final ErrorHandler QUIET_ERRORS = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Warnings do not affect the identity, so they are ignored silently.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

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
     * Returns the custom icon image of a {@code .utm} package. A custom icon is configured by
     * {@code Information/IconCustom = true} together with {@code Information/Icon}, which names
     * the image file. On macOS the file lies in {@code <package>/Data/<Icon>}; it is never
     * searched for in the package root. Empty when the flag is off, the name is absent or not a
     * plain supported image name, the file is missing, unreadable or too large, or its real path
     * is not inside the package, for example through a symbolic link.
     */
    public static Optional<Path> readCustomIcon(Path packageDirectory) {
        Path config = packageDirectory.resolve(CONFIG_FILE);

        if (!Files.isRegularFile(config)) {
            return Optional.empty();
        }

        try {
            if (Files.size(config) > MAX_CONFIG_BYTES) {
                return Optional.empty();
            }

            if (!booleanAt(config, INFORMATION_KEY, ICON_CUSTOM_KEY).orElse(false)) {
                return Optional.empty();
            }

            String fileName = stringAt(config, INFORMATION_KEY, ICON_KEY)
                    .map(String::trim)
                    .filter(UtmPackageIdentity::isPlainImageName)
                    .orElse(null);

            if (fileName == null) {
                return Optional.empty();
            }

            Path icon = packageDirectory.resolve(ICON_DIRECTORY).resolve(fileName);

            if (!Files.isRegularFile(icon) || Files.size(icon) > MAX_ICON_BYTES) {
                return Optional.empty();
            }

            // The real paths must agree, so a symbolic link cannot lead out of the package.
            Path realPackage = packageDirectory.toRealPath();
            Path realIcon = icon.toRealPath();

            return realIcon.startsWith(realPackage) ? Optional.of(realIcon) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A file name without a path separator or a parent reference, with a supported image suffix. */
    private static boolean isPlainImageName(String name) {
        return !name.isEmpty()
                && !name.contains("/")
                && !name.contains("\\")
                && !name.equals("..")
                && !name.equals(".")
                && isSupportedImage(name);
    }

    /** The image types that the overview can show. */
    private static boolean isSupportedImage(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp");
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
        return valueAt(config, keyPath)
                .filter(value -> "string".equals(value.getTagName()))
                .map(Element::getTextContent);
    }

    /**
     * Returns the boolean at a key path below the root dictionary, that is {@code <true/>} or
     * {@code <false/>} as the last step.
     */
    private static Optional<Boolean> booleanAt(Path config, String... keyPath) throws IOException {
        return valueAt(config, keyPath).flatMap(value -> switch (value.getTagName()) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> Optional.empty();
        });
    }

    /**
     * Returns the value element of the last key of a key path. Intermediate steps must be
     * dictionaries. The caller checks the tag of the value.
     */
    private static Optional<Element> valueAt(Path config, String... keyPath) throws IOException {
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
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Malformed input is an expected outcome here. The parser's default handler would
            // print it to stderr, so errors are rethrown to the caller and nothing is printed.
            builder.setErrorHandler(QUIET_ERRORS);
            document = builder.parse(config.toFile());
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
                return Optional.of(value);
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
