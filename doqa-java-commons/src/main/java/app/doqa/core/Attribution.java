package app.doqa.core;

import app.doqa.annotations.DoqaCaseIds;
import app.doqa.annotations.DoqaId;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the DoQA {@code externalId} for one test (priority top-down):
 * <ol>
 *   <li>{@code @DoqaId} on method (wins) or class &rarr; used verbatim. NB: a class-level
 *       {@code @DoqaId} applies to EVERY test of the class - sensible only for single-test
 *       classes; {@link DoqaSession} warns when several methods collapse into one id.</li>
 *   <li>id-in-title {@code [DOQA-123]} / {@code @DOQA:123} in the display name.</li>
 *   <li>native reuse - Allure {@code @AllureId} (read reflectively) &rarr; {@code ALLURE-<n>}.</li>
 *   <li>fallback - deterministic {@link SignatureHash} ({@code <framework>:<sha1>}).</li>
 * </ol>
 * {@code @DoqaCaseIds} (method then class) is captured independently.
 *
 * <p>Internal adapter API.
 */
public final class Attribution {

    private static final Logger LOG = Logger.getLogger(Attribution.class.getName());
    public static final Pattern ID_IN_TITLE = Pattern.compile("(?:\\[|@)DOQA[-:](\\d+)\\]?");
    private static final String ALLURE_ID_ANNOTATION = "io.qameta.allure.AllureId";
    private static final Set<String> WARNED_LONG_IDS = ConcurrentHashMap.newKeySet();

    private Attribution() {
    }

    public enum Source { EXPLICIT_EXTERNAL_ID, ID_IN_TITLE, NATIVE_ALLURE, SIGNATURE_HASH }

    public static final class Result {
        public final String externalId;
        public final Source source;
        public final long[] caseIds;     // nullable
        public final String allureId;    // nullable
        private final List<String> cascade;

        private Result(String externalId, Source source, long[] caseIds, String allureId,
                       List<String> cascade) {
            this.externalId = externalId;
            this.source = source;
            this.caseIds = caseIds;
            this.allureId = allureId;
            this.cascade = cascade;
        }

        /** The id the test reports under: runtime id first, then the cascade, placeholders substituted. */
        public String externalId(String runtimeId, Map<String, String> params) {
            List<String> ids = new ArrayList<>(cascade.size() + 1);
            if (runtimeId != null) {
                ids.add(runtimeId);
            }
            ids.addAll(cascade);
            for (int i = 0; i < ids.size(); i++) {
                String id = Placeholders.resolve(ids.get(i), params);
                if (fits(id) || i == ids.size() - 1) {
                    return id;
                }
                warnTooLong(id);
            }
            return null;
        }
    }

    public static Result resolve(TestRef ref) {
        String allureId = readAllureId(ref.testMethod);
        if (allureId == null) {
            allureId = readAllureId(ref.testClass);
        }
        if (allureId == null) {
            allureId = ref.allureId;
        }

        long[] caseIds = readCaseIds(ref.testMethod);
        if (caseIds == null) {
            caseIds = readCaseIds(ref.testClass);
        }
        caseIds = union(caseIds, ref.caseIds());

        List<String> ids = new ArrayList<>(4);
        List<Source> sources = new ArrayList<>(4);
        String explicit = readExternalId(ref.testMethod);
        if (explicit == null) {
            explicit = readExternalId(ref.testClass);
        }
        if (explicit == null) {
            explicit = ref.explicitId;
        }
        if (explicit != null && !explicit.trim().isEmpty()) {
            ids.add(explicit.trim());
            sources.add(Source.EXPLICIT_EXTERNAL_ID);
        }

        String fromTitle = ref.titleId != null && !ref.titleId.trim().isEmpty()
                ? ref.titleId.trim()
                : extractIdInTitle(ref.displayName);
        if (fromTitle != null) {
            ids.add(fromTitle);
            sources.add(Source.ID_IN_TITLE);
        }

        if (allureId != null && !allureId.trim().isEmpty()) {
            ids.add("ALLURE-" + allureId.trim());
            sources.add(Source.NATIVE_ALLURE);
        }

        String signature = ref.signature != null
                ? ref.signature
                : SignatureHash.stableSignature(ref.fqcn, ref.methodName, ref.methodParamTypes,
                        ref.displayName, ref.parameterized);
        ids.add(SignatureHash.fallbackExternalId(ref.framework(), signature));
        sources.add(Source.SIGNATURE_HASH);

        // a template is judged once its placeholders are substituted (Result#externalId)
        int chosen = 0;
        while (chosen < ids.size() - 1 && !fits(ids.get(chosen))
                && !Placeholders.hasPlaceholder(ids.get(chosen))) {
            warnTooLong(ids.get(chosen));
            chosen++;
        }
        return new Result(ids.get(chosen), sources.get(chosen), caseIds, allureId,
                Collections.unmodifiableList(new ArrayList<>(ids.subList(chosen, ids.size()))));
    }

    private static boolean fits(String id) {
        return id == null || id.length() <= Limits.MAX_EXTERNAL_ID;
    }

    private static void warnTooLong(String id) {
        if (WARNED_LONG_IDS.add(id)) {
            LOG.warning("DoQA: externalId \"" + Limits.clip(id, 80) + "\" is longer than "
                    + Limits.MAX_EXTERNAL_ID + " characters and would be rejected by the server"
                    + " - the next source of the id is used instead");
        }
    }

    private static long[] union(long[] a, long[] b) {
        if (b == null || b.length == 0) {
            return a;
        }
        if (a == null || a.length == 0) {
            return b;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (long v : a) {
            seen.add(v);
        }
        for (long v : b) {
            seen.add(v);
        }
        long[] out = new long[seen.size()];
        int i = 0;
        for (Long v : seen) {
            out[i++] = v;
        }
        return out;
    }

    public static String extractIdInTitle(String displayName) {
        if (displayName == null) {
            return null;
        }
        Matcher m = ID_IN_TITLE.matcher(displayName);
        return m.find() ? "DOQA-" + m.group(1) : null;
    }

    private static String readExternalId(AnnotatedElement element) {
        if (element == null) {
            return null;
        }
        DoqaId a = element.getAnnotation(DoqaId.class);
        return a == null ? null : a.value();
    }

    private static long[] readCaseIds(AnnotatedElement element) {
        if (element == null) {
            return null;
        }
        DoqaCaseIds a = element.getAnnotation(DoqaCaseIds.class);
        if (a == null || a.value().length == 0) {
            return null;
        }
        return a.value();
    }

    private static String readAllureId(AnnotatedElement element) {
        if (element == null) {
            return null;
        }
        for (Annotation annotation : element.getAnnotations()) {
            if (ALLURE_ID_ANNOTATION.equals(annotation.annotationType().getName())) {
                try {
                    Method valueMethod = annotation.annotationType().getMethod("value");
                    Object value = valueMethod.invoke(annotation);
                    return value == null ? null : String.valueOf(value);
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
