package app.doqa.cucumber;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Keys {

    private static final String NAMING = "cucumber.junit-platform.naming-strategy";
    private static final String CONSTANTS = "io.cucumber.junit.platform.engine.Constants";

    private Keys() {
    }

    static String hash(String signature) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(signature.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("cucumber:");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Map<String, String>> namingStrategies() {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of(NAMING, "short"));
        out.add(Map.of(NAMING, "long"));
        if (hasConstant("JUNIT_PLATFORM_SHORT_NAMING_STRATEGY_EXAMPLE_NAME_PROPERTY_NAME")) {
            out.add(Map.of(NAMING, "short", NAMING + ".short.example-name", "pickle"));
            out.add(Map.of(NAMING, "short", NAMING + ".short.example-name", "number"));
            out.add(Map.of(NAMING, "long", NAMING + ".long.example-name", "pickle"));
        }
        if (hasConstant("JUNIT_PLATFORM_SUREFIRE_NAMING_STRATEGY_EXAMPLE_NAME_PROPERTY_NAME")) {
            out.add(Map.of(NAMING, "surefire"));
            out.add(Map.of(NAMING, "surefire", NAMING + ".surefire.example-name", "pickle"));
        }
        return out;
    }

    private static boolean hasConstant(String name) {
        try {
            Class.forName(CONSTANTS).getField(name);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
