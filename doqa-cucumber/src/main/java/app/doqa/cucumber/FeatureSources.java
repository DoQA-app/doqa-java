package app.doqa.cucumber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JVM-wide registry of feature files by the URI Cucumber reports them under. */
final class FeatureSources {

    private static final Logger LOG = Logger.getLogger(FeatureSources.class.getName());
    private static final String CLASSPATH = "classpath:";

    private static final ConcurrentMap<String, FeatureFile> FILES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> PATHS = new ConcurrentHashMap<>();

    private FeatureSources() {
    }

    static void register(String uri, String source) {
        if (uri != null && source != null) {
            FILES.put(uri, FeatureFile.of(source));
        }
    }

    static FeatureFile file(String uri) {
        FeatureFile known = FILES.get(uri);
        if (known != null) {
            return known;
        }
        String source = read(uri);
        if (source == null) {
            return null;
        }
        FeatureFile file = FeatureFile.of(source);
        FeatureFile raced = FILES.putIfAbsent(uri, file);
        return raced != null ? raced : file;
    }

    // a file: URI resolves to the shortest classpath tail with the same content - the build copies resources
    static String path(String uri) {
        return PATHS.computeIfAbsent(uri, FeatureSources::computePath);
    }

    private static String computePath(String uri) {
        if (uri.startsWith(CLASSPATH)) {
            return classpathName(uri);
        }
        try {
            URI parsed = URI.create(uri);
            if ("file".equalsIgnoreCase(parsed.getScheme())) {
                return filePath(Paths.get(parsed));
            }
            String ssp = parsed.getSchemeSpecificPart();
            int inJar = ssp == null ? -1 : ssp.lastIndexOf("!/");
            if (inJar >= 0) {
                return stripSlashes(ssp.substring(inJar + 2));
            }
            if (parsed.getScheme() == null && parsed.getPath() != null) {
                return stripSlashes(parsed.getPath());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "DoQA cucumber: cannot parse feature uri " + uri, e);
        }
        return stripSlashes(uri.replace('\\', '/'));
    }

    private static String filePath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        int count = absolute.getNameCount();
        byte[] content = null;
        for (int k = 1; k <= count; k++) {
            String tail = absolute.subpath(count - k, count).toString().replace('\\', '/');
            URL resource = resource(tail);
            if (resource == null) {
                continue;
            }
            if (sameFile(resource, absolute)) {
                return tail;
            }
            if (content == null) {
                content = readBytes(absolute);
            }
            if (content != null && Arrays.equals(content, readBytes(resource))) {
                return tail;
            }
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path relative = absolute.startsWith(cwd) ? cwd.relativize(absolute) : absolute;
        return relative.toString().replace('\\', '/');
    }

    private static boolean sameFile(URL resource, Path file) {
        if (!"file".equalsIgnoreCase(resource.getProtocol())) {
            return false;
        }
        try {
            return Files.isSameFile(Paths.get(resource.toURI()), file);
        } catch (Exception e) {
            return false;
        }
    }

    private static URL resource(String name) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        URL url = context == null ? null : context.getResource(name);
        return url != null ? url : FeatureSources.class.getClassLoader().getResource(name);
    }

    private static String read(String uri) {
        byte[] bytes = null;
        if (uri.startsWith(CLASSPATH)) {
            URL url = resource(classpathName(uri));
            bytes = url == null ? null : readBytes(url);
        } else {
            try {
                URI parsed = URI.create(uri);
                bytes = "file".equalsIgnoreCase(parsed.getScheme())
                        ? readBytes(Paths.get(parsed))
                        : readBytes(parsed.toURL());
            } catch (Exception e) {
                LOG.log(Level.FINE, "DoQA cucumber: cannot read feature " + uri, e);
            }
        }
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] readBytes(URL url) {
        try (InputStream in = url.openStream()) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String classpathName(String uri) {
        return stripSlashes(decode(uri.substring(CLASSPATH.length())));
    }

    // the key must not depend on whether the runner reported the path percent-encoded; '+' stays literal as in URIs
    static String decode(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(path.length());
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); ) {
            int hi;
            int lo;
            if (path.charAt(i) == '%' && i + 2 < path.length()
                    && (hi = Character.digit(path.charAt(i + 1), 16)) >= 0
                    && (lo = Character.digit(path.charAt(i + 2), 16)) >= 0) {
                bytes.write(hi << 4 | lo);
                i += 3;
                continue;
            }
            flush(bytes, out);
            out.append(path.charAt(i++));
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    private static String stripSlashes(String path) {
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') {
            i++;
        }
        return path.substring(i);
    }

    static void reset() {
        FILES.clear();
        PATHS.clear();
    }
}
