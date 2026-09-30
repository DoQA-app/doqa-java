package app.doqa.cucumber;

import app.doqa.core.Placeholders;
import app.doqa.core.TestRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Identity of one scenario or Examples row: key {@code cucumber:sha1(<path>#[<rule>/]<scenario>)}. */
final class ScenarioModel {

    static final String FRAMEWORK = "cucumber";
    private static final String EXTENSION = ".feature";

    final String path;
    final String featureName;
    final String ruleName;
    final String name;
    final String displayName;
    final int scenarioLine;
    final boolean outline;
    final Map<String, String> parameters;
    final DoqaTags tags;

    private ScenarioModel(String path, String featureName, String ruleName, String name,
                          String displayName, int scenarioLine, boolean outline,
                          Map<String, String> parameters, DoqaTags tags) {
        this.path = path;
        this.featureName = featureName;
        this.ruleName = ruleName;
        this.name = name;
        this.displayName = displayName;
        this.scenarioLine = scenarioLine;
        this.outline = outline;
        this.parameters = parameters;
        this.tags = tags;
    }

    static ScenarioModel of(String uri, FeatureFile file, int ruleLine, int scenarioLine,
                            int examplesLine, int rowLine) {
        String path = FeatureSources.path(uri);
        int featureLine = file.featureLine();
        String ruleName = ruleLine > 0 ? file.nameAt(ruleLine) : null;
        String name = file.nameAt(scenarioLine);
        boolean outline = examplesLine > 0 && rowLine > 0;

        Map<String, String> parameters = new LinkedHashMap<>();
        if (outline) {
            List<String> header = file.examplesHeader(examplesLine);
            List<String> row = file.rowCells(rowLine);
            for (int i = 0; i < header.size() && i < row.size(); i++) {
                parameters.putIfAbsent(header.get(i), row.get(i));
            }
        }

        List<List<String>> levels = new ArrayList<>(4);
        levels.add(featureLine > 0 ? file.tagsAbove(featureLine) : Collections.<String>emptyList());
        levels.add(ruleLine > 0 ? file.tagsAbove(ruleLine) : Collections.<String>emptyList());
        levels.add(file.tagsAbove(scenarioLine));
        levels.add(outline ? file.tagsAbove(examplesLine) : Collections.<String>emptyList());
        DoqaTags tags = DoqaTags.resolve(levels, 2, name, path + ":" + scenarioLine);

        return new ScenarioModel(path, featureName(file, featureLine), ruleName, name,
                interpolate(name, parameters), scenarioLine, outline,
                Collections.unmodifiableMap(parameters), tags);
    }

    static String interpolate(String template, Map<String, String> row) {
        String out = template;
        for (Map.Entry<String, String> e : row.entrySet()) {
            out = out.replace("<" + e.getKey() + ">", e.getValue());
        }
        return out;
    }

    private static String featureName(FeatureFile file, int featureLine) {
        return featureLine > 0 ? file.nameAt(featureLine) : "";
    }

    String signature() {
        return path + "#" + (ruleName != null ? ruleName + "/" : "") + name;
    }

    String namespace() {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : null;
    }

    String featureKey() {
        return path.endsWith(EXTENSION) ? path.substring(0, path.length() - EXTENSION.length()) : path;
    }

    TestRef testRef() {
        TestRef.Builder b = new TestRef.Builder()
                .fqcn(featureKey())
                .methodName(name)
                .displayName(displayName)
                .signature(signature())
                .namespace(namespace())
                .classname(featureName)
                .framework(FRAMEWORK, FRAMEWORK)
                .methodKey(path + ":" + scenarioLine)
                .explicitId(Placeholders.resolve(tags.explicitId, parameters))
                .titleId(tags.titleId)
                .allureId(tags.allureId);
        if (tags.caseIds != null) {
            b.caseIds(tags.caseIds);
        }
        return b.build();
    }

    List<Object[]> parameterPairs() {
        List<Object[]> pairs = new ArrayList<>(parameters.size());
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            pairs.add(new Object[]{e.getKey(), e.getValue()});
        }
        return pairs;
    }
}
