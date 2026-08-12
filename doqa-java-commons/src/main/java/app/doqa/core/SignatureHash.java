package app.doqa.core;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic fallback {@code externalId} from the test signature.
 * Formula: {@code externalId = "<framework>:" + sha1(stableSignature)} (framework from
 * {@link AdapterRuntime}), where the signature is {@code fqcn#method(paramTypes)} + display-name
 * (non-parameterized). Parameterized invocations collapse to the method-level key (args travel
 * in {@code parameters[]}). The display name is taken verbatim - names that differ only by an
 * index (dynamic tests {@code "[1] x"} / {@code "[2] x"}) stay distinct tests.
 *
 * <p>Internal adapter API.
 */
public final class SignatureHash {

    private SignatureHash() {
    }

    public static String stableSignature(String fqcn, String methodName, String methodParamTypes,
                                         String displayName, boolean isParameterized) {
        StringBuilder sb = new StringBuilder();
        sb.append(fqcn == null ? "" : fqcn);
        if (methodName != null && !methodName.isEmpty()) {
            sb.append('#').append(methodName);
            if (methodParamTypes != null && !methodParamTypes.trim().isEmpty()) {
                sb.append('(').append(methodParamTypes.trim()).append(')');
            }
        }
        if (!isParameterized) {
            String dn = displayName == null ? "" : displayName.trim();
            if (!dn.isEmpty()) {
                sb.append('#').append(dn);
            }
        }
        return sb.toString();
    }

    /**
     * Parameter types of a resolved method as {@code "java.lang.String, int"} - the shape Jupiter's
     * {@code MethodSource.getMethodParameterTypes()} hands out, reconstructed for the frameworks
     * that only offer the {@link Method}. The value feeds the signature above, so the format must
     * stay stable: changing it changes every fallback id.
     */
    public static String parameterTypes(Method method) {
        if (method == null) {
            return null;
        }
        Class<?>[] types = method.getParameterTypes();
        if (types.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getTypeName());
        }
        return sb.toString();
    }

    public static String fallbackExternalId(String stableSignature) {
        return AdapterRuntime.framework() + ":" + sha1Hex(stableSignature);
    }

    static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(v));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
