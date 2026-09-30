package app.doqa.cucumber;

import app.doqa.core.Attribution;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DoQA service tags of one scenario: {@code @doqa.id:}, {@code @DOQA-<n>}, {@code @allure.id:}, {@code @doqa.case:}. */
final class DoqaTags {

    private static final Logger LOG = Logger.getLogger(DoqaTags.class.getName());
    private static final Pattern DOQA_N = Pattern.compile("DOQA[-:=](\\d+)");
    // warned once per JVM: the discovery filter and the plugin resolve the same tags
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    final String explicitId;
    final String titleId;
    final String allureId;
    final long[] caseIds;
    final List<String> tags;

    private DoqaTags(String explicitId, String titleId, String allureId, long[] caseIds,
                     List<String> tags) {
        this.explicitId = explicitId;
        this.titleId = titleId;
        this.allureId = allureId;
        this.caseIds = caseIds;
        this.tags = tags;
    }

    static DoqaTags resolve(List<List<String>> levels, int scenarioLevel, String scenarioName,
                            String where) {
        int n = levels.size();
        List<List<String>> explicit = new ArrayList<>(n);
        List<List<String>> title = new ArrayList<>(n);
        List<List<String>> allure = new ArrayList<>(n);
        Set<Long> cases = new LinkedHashSet<>();
        Set<String> plain = new LinkedHashSet<>();
        for (int level = 0; level < n; level++) {
            List<String> e = new ArrayList<>();
            List<String> t = new ArrayList<>();
            List<String> a = new ArrayList<>();
            for (String tag : levels.get(level)) {
                classify(tag, e, t, a, cases, plain, where);
            }
            if (level == scenarioLevel) {
                String fromName = Attribution.extractIdInTitle(scenarioName);
                if (fromName != null) {
                    t.add(fromName);
                }
            }
            explicit.add(e);
            title.add(t);
            allure.add(a);
        }
        long[] caseIds = new long[cases.size()];
        int i = 0;
        for (Long id : cases) {
            caseIds[i++] = id;
        }
        return new DoqaTags(narrowest(explicit, "@doqa.id", where),
                narrowest(title, "@DOQA-<n>", where),
                narrowest(allure, "@allure.id", where),
                caseIds.length == 0 ? null : caseIds,
                new ArrayList<>(plain));
    }

    static boolean isServiceTag(String tag) {
        if (DOQA_N.matcher(tag).matches()) {
            return true;
        }
        String key = key(tag);
        return key != null && (key.equals("doqa.id") || key.equals("doqa.case")
                || key.equals("allure.id"));
    }

    private static void classify(String tag, List<String> explicit, List<String> title,
                                 List<String> allure, Set<Long> cases, Set<String> plain,
                                 String where) {
        Matcher doqaN = DOQA_N.matcher(tag);
        if (doqaN.matches()) {
            title.add("DOQA-" + doqaN.group(1));
            return;
        }
        String key = key(tag);
        if (key == null || !isServiceTag(tag)) {
            plain.add(tag);
            return;
        }
        String value = tag.substring(key.length() + 1).trim();
        if (value.isEmpty()) {
            warn("tag @" + tag + " at " + where + " has no value and is ignored");
            return;
        }
        switch (key) {
            case "doqa.id":
                explicit.add(value);
                break;
            case "allure.id":
                allure.add(value);
                break;
            default:
                try {
                    cases.add(Long.parseLong(value));
                } catch (NumberFormatException e) {
                    warn("tag @" + tag + " at " + where + " is not a numeric case id and is"
                            + " ignored (repeat the tag to bind several cases)");
                }
                break;
        }
    }

    private static String key(String tag) {
        int colon = tag.indexOf(':');
        int equals = tag.indexOf('=');
        int sep = colon < 0 ? equals : equals < 0 ? colon : Math.min(colon, equals);
        return sep <= 0 ? null : tag.substring(0, sep).toLowerCase(Locale.ROOT);
    }

    private static String narrowest(List<List<String>> byLevel, String tagName, String where) {
        for (int level = byLevel.size() - 1; level >= 0; level--) {
            List<String> values = byLevel.get(level);
            if (values.isEmpty()) {
                continue;
            }
            Set<String> distinct = new LinkedHashSet<>(values);
            if (distinct.size() > 1) {
                warn("scenario at " + where + " has several " + tagName + " values on one level "
                        + distinct + " - the first one, " + values.get(0) + ", is used");
            }
            return values.get(0);
        }
        return null;
    }

    private static void warn(String message) {
        if (WARNED.add(message)) {
            LOG.warning("DoQA cucumber: " + message);
        }
    }
}
