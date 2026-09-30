package app.doqa.cucumber;

import app.doqa.core.PlanSelection;
import java.util.List;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.PostDiscoveryFilter;

/** Mode-0 selective execution for Cucumber; side-effect free because Surefire discovers twice. */
public class DoqaCucumberSelectFilter implements PostDiscoveryFilter {

    private static final String ENGINE = "engine";
    private static final String CUCUMBER_ENGINE = "cucumber";

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        try {
            return applySafely(descriptor);
        } catch (Throwable t) {
            return FilterResult.included("DoQA selection failed to resolve, keeping: " + t);
        }
    }

    private static FilterResult applySafely(TestDescriptor descriptor) {
        Coordinates at = Coordinates.of(descriptor.getUniqueId());
        if (at == null || !at.isScenario(descriptor.isTest())) {
            return FilterResult.included("not a Cucumber scenario");
        }
        if (!PlanSelection.active()) {
            return FilterResult.included("DoQA mode-0 selection not active");
        }
        FeatureFile file = FeatureSources.file(at.uri);
        if (file == null) {
            return FilterResult.included("feature unreadable, keeping: " + at.uri);
        }
        ScenarioModel model = ScenarioModel.of(at.uri, file, at.ruleLine, at.scenarioLine,
                at.examplesLine, at.rowLine);
        if (PlanSelection.allows(model.testRef())) {
            return FilterResult.included("selected: " + model.signature());
        }
        return FilterResult.excluded("deselected (not in run's autotest list): "
                + model.signature());
    }

    static final class Coordinates {
        String uri;
        int ruleLine;
        int scenarioLine;
        int examplesLine;
        int rowLine;
        private String last;

        static Coordinates of(UniqueId id) {
            List<UniqueId.Segment> segments = id.getSegments();
            int engine = -1;
            for (int i = 0; i < segments.size(); i++) {
                UniqueId.Segment s = segments.get(i);
                if (ENGINE.equals(s.getType()) && CUCUMBER_ENGINE.equals(s.getValue())) {
                    engine = i;
                }
            }
            if (engine < 0) {
                return null;
            }
            Coordinates at = new Coordinates();
            for (int i = engine + 1; i < segments.size(); i++) {
                UniqueId.Segment s = segments.get(i);
                switch (s.getType()) {
                    case "feature":
                        at.uri = s.getValue();
                        break;
                    case "rule":
                        at.ruleLine = line(s);
                        break;
                    case "scenario":
                        at.scenarioLine = line(s);
                        break;
                    case "examples":
                        at.examplesLine = line(s);
                        break;
                    case "example":
                        at.rowLine = line(s);
                        break;
                    default:
                        return null;
                }
                at.last = s.getType();
            }
            return at.uri == null ? null : at;
        }

        boolean isScenario(boolean test) {
            if ("example".equals(last)) {
                return scenarioLine > 0 && examplesLine > 0 && rowLine > 0;
            }
            return test && "scenario".equals(last) && scenarioLine > 0;
        }

        private static int line(UniqueId.Segment segment) {
            return Integer.parseInt(segment.getValue());
        }
    }
}
