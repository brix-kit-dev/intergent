package aie.guard.supply;

import aie.guard.supply.DependencyLock.Entry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * S-9: reads the license of every third-party jar inside {@code engine.jar}.
 *
 * <p>A POM with several licenses offers a choice, as the Maven POM reference defines it, so one allowed name is
 * enough. A jar with no allowed license fails, also when its license is unknown.
 */
public final class EngineLicenses {

    private static final String LIBRARIES = "BOOT-INF/lib/";
    private static final String OWN_MODULE_MARKER = "META-INF/maven/aie/";
    private static final String ALLOW = "allow ";

    private EngineLicenses() {
    }

    public static Set<String> readPolicy(Path file) throws IOException {
        Set<String> allowed = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (!line.startsWith(ALLOW)) {
                throw new IllegalArgumentException("S-9: bad line in " + file + ": " + line);
            }
            allowed.add(line.substring(ALLOW.length()).trim());
        }
        return allowed;
    }

    public static List<String> violations(Path engineJar, List<Entry> lock, Path repository, Set<String> allowed)
            throws IOException {
        Map<String, String> pathsByHash = lock.stream()
                .collect(Collectors.toMap(Entry::sha256, Entry::path, (first, second) -> first));
        List<String> problems = new ArrayList<>();
        int jars = 0;
        try (ZipFile archive = new ZipFile(engineJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith(LIBRARIES) && entry.getName().endsWith(".jar")) {
                    jars++;
                    byte[] jar = read(archive, entry);
                    if (!isOwnModule(jar)) {
                        String name = entry.getName().substring(LIBRARIES.length());
                        check(name, pathsByHash.get(DependencyLock.sha256(jar)), repository, allowed, problems);
                    }
                }
            }
        }
        if (jars == 0) {
            problems.add("S-9: no jars found in " + engineJar);
        }
        return problems;
    }

    private static void check(String name, String path, Path repository, Set<String> allowed, List<String> problems)
            throws IOException {
        if (path == null) {
            problems.add("S-9: jar in the engine is not in the lock: " + name);
            return;
        }
        List<String> licenses = licenses(repository.resolve(pomOf(path)), repository);
        if (licenses.isEmpty()) {
            problems.add("S-9: no license found for " + name);
        } else if (licenses.stream().noneMatch(allowed::contains)) {
            problems.add("S-9: license not allowed for " + name + ": " + licenses);
        }
    }

    /** The nearest POM with a licenses section wins, as in Maven's own inheritance. */
    static List<String> licenses(Path pom, Path repository) throws IOException {
        Path current = pom;
        Set<Path> seen = new HashSet<>();
        while (current != null && Files.isRegularFile(current) && seen.add(current)) {
            Element project = parse(current);
            List<String> names = children(project, "licenses").stream()
                    .flatMap(licenses -> children(licenses, "license").stream())
                    .map(license -> text(license, "name"))
                    .toList();
            if (!names.isEmpty()) {
                return names;
            }
            current = children(project, "parent").stream().findFirst().map(parent -> parentPom(parent, repository)).orElse(null);
        }
        return List.of();
    }

    private static Path parentPom(Element parent, Path repository) {
        String artifact = text(parent, "artifactId");
        String version = text(parent, "version");
        return repository.resolve(text(parent, "groupId").replace('.', '/'))
                .resolve(artifact).resolve(version).resolve(artifact + "-" + version + ".pom");
    }

    static String pomOf(String jarPath) {
        String[] parts = jarPath.split("/");
        String directory = jarPath.substring(0, jarPath.lastIndexOf('/'));
        return directory + "/" + parts[parts.length - 3] + "-" + parts[parts.length - 2] + ".pom";
    }

    private static boolean isOwnModule(byte[] jar) throws IOException {
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (entry.getName().startsWith(OWN_MODULE_MARKER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] read(ZipFile archive, ZipEntry entry) throws IOException {
        try (InputStream content = archive.getInputStream(entry)) {
            return content.readAllBytes();
        }
    }

    private static Element parse(Path pom) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pom.toFile()).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("S-9: cannot read " + pom, e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                found.add(element);
            }
        }
        return found;
    }

    private static String text(Element parent, String name) {
        return children(parent, name).stream().findFirst().map(Node::getTextContent).map(String::trim).orElse("");
    }
}
