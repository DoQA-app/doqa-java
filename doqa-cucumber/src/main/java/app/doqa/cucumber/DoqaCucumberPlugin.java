package app.doqa.cucumber;

import app.doqa.core.AdapterRuntime;
import app.doqa.core.AttachmentRef;
import app.doqa.core.DoqaContexts;
import app.doqa.core.DoqaSession;
import app.doqa.core.PlanSelection;
import app.doqa.core.ResultBuilder;
import app.doqa.core.RuntimeContext;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Node;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestSourceParsed;
import io.cucumber.plugin.event.TestSourceRead;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.cucumber.plugin.event.WriteEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Cucumber-JVM plugin: reports every scenario as a DoQA autotest. */
public final class DoqaCucumberPlugin implements ConcurrentEventListener {

    private static final Logger LOG = Logger.getLogger(DoqaCucumberPlugin.class.getName());

    private static final ConcurrentMap<String, Collection<Node>> NODES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, ScenarioRun> RUNS = new ConcurrentHashMap<>();

    private volatile DoqaSession session;
    private volatile boolean sessionFailed;
    private final AtomicInteger executed = new AtomicInteger();
    private final AtomicInteger inRun = new AtomicInteger();

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        AdapterRuntime.markCucumberPluginCreated();
        publisher.registerHandlerFor(TestRunStarted.class, e -> safely("run start", this::runStarted));
        publisher.registerHandlerFor(TestSourceRead.class, e -> safely("feature source",
                () -> FeatureSources.register(e.getUri().toString(), e.getSource())));
        publisher.registerHandlerFor(TestSourceParsed.class, e -> safely("feature tree",
                () -> NODES.put(e.getUri().toString(), e.getNodes())));
        publisher.registerHandlerFor(TestCaseStarted.class, e -> safely("scenario start",
                () -> caseStarted(e)));
        publisher.registerHandlerFor(TestStepStarted.class, e -> safely("step start",
                () -> stepStarted(e)));
        publisher.registerHandlerFor(TestStepFinished.class, e -> safely("step finish",
                () -> stepFinished(e)));
        publisher.registerHandlerFor(EmbedEvent.class, e -> safely("attachment", () -> embed(e)));
        publisher.registerHandlerFor(WriteEvent.class, e -> safely("log", () -> write(e)));
        publisher.registerHandlerFor(TestCaseFinished.class, e -> safely("scenario finish",
                () -> caseFinished(e)));
        publisher.registerHandlerFor(TestRunFinished.class, e -> safely("run finish",
                this::runFinished));
    }

    private void runStarted() {
        session();
    }

    private void runFinished() {
        DoqaSession local = session();
        if (local == null) {
            return;
        }
        DoqaSession.awaitPendingReports();
        warnOnEmptySelection();
        local.flush();
    }

    private void warnOnEmptySelection() {
        if (executed.get() == 0 || inRun.get() > 0 || !PlanSelection.active()) {
            return;
        }
        LOG.warning("DoQA cucumber: the DoQA run selects none of the " + executed.get()
                + " scenario(s) executed here, so none was reported. Check that the autotests"
                + " of the run come from these features, and pass the run's native filter to"
                + " Cucumber (-Dcucumber.filter.name) so the other scenarios do not run at all.");
    }

    private void caseStarted(TestCaseStarted event) {
        DoqaSession local = session();
        if (local == null) {
            return;
        }
        TestCase testCase = event.getTestCase();
        String uri = testCase.getUri().toString();
        FeatureFile file = FeatureSources.file(uri);
        ScenarioModel model = model(uri, file, testCase);
        if (model == null) {
            return;
        }
        RuntimeContext ctx = DoqaContexts.open(uniqueId(testCase));
        ctx.testRef = model.testRef();
        ctx.tStart = millis(event.getInstant());
        if (model.outline) {
            ctx.definitionName = model.name;
            ctx.invocationParameters.addAll(model.parameterPairs());
        }
        ctx.tags.addAll(model.tags.tags);
        RUNS.put(testCase.getId(), new ScenarioRun(ctx, model, file));
    }

    private void stepStarted(TestStepStarted event) {
        ScenarioRun run = RUNS.get(event.getTestCase().getId());
        if (run != null) {
            DoqaContexts.bind(run.ctx.uniqueId);
            run.stepStarted(event.getTestStep(), millis(event.getInstant()));
        }
    }

    private void stepFinished(TestStepFinished event) {
        ScenarioRun run = RUNS.get(event.getTestCase().getId());
        if (run != null) {
            run.stepFinished(event.getTestStep(), event.getResult());
        }
    }

    private void embed(EmbedEvent event) {
        ScenarioRun run = RUNS.get(event.getTestCase().getId());
        if (run != null) {
            String name = event.getName() == null || event.getName().trim().isEmpty()
                    ? "attachment" : event.getName();
            run.attach(AttachmentRef.ofBytes(name, event.getData(), event.getMediaType()));
        }
    }

    private void write(WriteEvent event) {
        ScenarioRun run = RUNS.get(event.getTestCase().getId());
        if (run != null) {
            run.log(event.getText());
        }
    }

    private void caseFinished(TestCaseFinished event) {
        TestCase testCase = event.getTestCase();
        ScenarioRun run = RUNS.remove(testCase.getId());
        DoqaSession local = session;
        if (run == null || local == null) {
            return;
        }
        run.finish();
        RuntimeContext ctx = DoqaContexts.remove(run.ctx.uniqueId);
        if (ctx == null) {
            ctx = run.ctx;
        }
        ctx.tEnd = millis(event.getInstant());
        Outcome outcome = Outcome.of(event.getResult(), run.firstUndefinedStep);
        executed.incrementAndGet();
        RuntimeContext built = ctx;
        local.reportAsync(() -> {
            ResultBuilder.Built result = ResultBuilder.build(built, outcome.outcome, outcome.message,
                    outcome.traces, local.uploader(), local::allowsId, local.config);
            if (result != null) {
                inRun.incrementAndGet();
            }
            return result;
        });
        if (local.realtime) {
            // Cucumber has no "feature finished" event: stream after every scenario
            String featureKey = run.model.featureKey();
            local.reportAsync(() -> {
                local.flushClass(featureKey);
                return null;
            });
        }
    }

    private static ScenarioModel model(String uri, FeatureFile file, TestCase testCase) {
        if (file == null) {
            LOG.warning("DoQA cucumber: cannot read the feature " + uri + " - scenario \""
                    + testCase.getName() + "\" is not reported");
            return null;
        }
        int line = testCase.getLocation().getLine();
        int ruleLine = 0;
        int scenarioLine = line;
        int examplesLine = 0;
        int rowLine = 0;
        Collection<Node> nodes = NODES.get(uri);
        List<Node> path = nodes == null ? null : pathTo(nodes, line);
        if (path != null) {
            for (Node node : path) {
                int at = node.getLocation().getLine();
                if (node instanceof Node.Rule) {
                    ruleLine = at;
                } else if (node instanceof Node.Scenario || node instanceof Node.ScenarioOutline) {
                    scenarioLine = at;
                } else if (node instanceof Node.Examples) {
                    examplesLine = at;
                } else if (node instanceof Node.Example) {
                    rowLine = at;
                }
            }
        }
        return ScenarioModel.of(uri, file, ruleLine, scenarioLine, examplesLine, rowLine);
    }

    private static List<Node> pathTo(Collection<Node> nodes, int line) {
        for (Node root : nodes) {
            Optional<List<Node>> path = root.findPathTo(n -> n.getLocation().getLine() == line
                    && (n instanceof Node.Scenario || n instanceof Node.Example));
            if (path.isPresent()) {
                return new ArrayList<>(path.get());
            }
        }
        return null;
    }

    private DoqaSession session() {
        DoqaSession local = session;
        if (local == null) {
            if (sessionFailed) {
                return null;
            }
            try {
                local = DoqaSession.getOrInit();
            } catch (Throwable t) {
                sessionFailed = true;
                LOG.log(Level.WARNING, "DoQA cucumber: cannot start reporting, the scenarios of this"
                        + " run are not reported", t);
                return null;
            }
            session = local;
        }
        return local.enabled ? local : null;
    }

    private static String uniqueId(TestCase testCase) {
        return "cucumber:" + testCase.getId();
    }

    private static long millis(Instant instant) {
        return instant == null ? System.currentTimeMillis() : instant.toEpochMilli();
    }

    // since Cucumber 8 an exception thrown by a plugin fails the scenario or the run
    private static void safely(String what, Runnable handler) {
        try {
            handler.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "DoQA cucumber: " + what + " handling failed", t);
        }
    }
}
