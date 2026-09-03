package app.doqa.core;

import app.doqa.client.Attachment;
import app.doqa.client.AutotestDef;
import app.doqa.client.AutotestResult;
import app.doqa.client.DoqaConfig;
import app.doqa.client.Link;
import app.doqa.client.Outcome;
import app.doqa.client.Parameter;
import app.doqa.client.Step;
import app.doqa.client.StepKind;
import app.doqa.client.StepResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Projects a finished test ({@link RuntimeContext} + {@link TestRef} + resolved outcome) into
 * client-core {@link AutotestDef} (upsert) and {@link AutotestResult} (results): def steps bucket
 * setup&rarr;before / call&rarr;step / teardown&rarr;after; parameters form a list (parameterized
 * args + runtime {@code addParameter}); attachments are uploaded first to get
 * {@code media_file_id}s. Messages, traces and parameter values are truncated to the configured
 * limits so a pathological assertion diff cannot blow up the payload.
 *
 * <p>Internal adapter API.
 */
public final class ResultBuilder {

    /** Uploads one attachment, returning its media file id (or null on failure). */
    public interface AttachmentUploader {
        String upload(AttachmentRef ref);
    }

    /** Membership gate: mode-0 selection - a disallowed id skips the build (and its uploads). */
    public interface IdGate {
        boolean allows(String externalId);
    }

    public static final class Built {
        public final AutotestDef def;
        public final AutotestResult result;
        public final String fullName;   // adapter-side metadata for the file sink (Allure fullName)
        public final String allureId;   // native @AllureId, preserved 1:1 as the AS_ID label
        public final String classKey;   // top-level test class - links class fixtures (@BeforeAll/@AfterAll)
        public final String methodKey;  // declaring method - duplicate-externalId diagnostics

        public Built(AutotestDef def, AutotestResult result, String fullName, String allureId,
                     String classKey, String methodKey) {
            this.def = def;
            this.result = result;
            this.fullName = fullName;
            this.allureId = allureId;
            this.classKey = classKey;
            this.methodKey = methodKey;
        }
    }

    private static final Pattern PARAM_DISPLAY = Pattern.compile("^\\[(\\d+)\\]\\s*(.*)$");

    private ResultBuilder() {
    }

    /**
     * Builds the def/result pair, or returns {@code null} when {@code gate} rejects the resolved
     * externalId - nothing (including attachments) is uploaded for an out-of-scope test.
     */
    public static Built build(RuntimeContext ctx, String outcome, String message, String traces,
                              AttachmentUploader uploader, IdGate gate, DoqaConfig limits) {
        TestRef ref = ctx.testRef;
        Meta meta = MetaReader.read(ref);
        Attribution.Result attr = Attribution.resolve(ref);

        // {param} placeholders in annotation values: substituted from the captured invocation
        // args + runtime addParameter, before any identity/selection use.
        Map<String, String> params = Placeholders.paramsOf(ctx);

        // runtime override (Doqa.addExternalId) wins over any annotation-derived id - the only
        // way dynamic tests can pin a stable explicit id.
        String externalId = Placeholders.resolve(
                ctx.externalId != null ? ctx.externalId : attr.externalId, params);
        if (gate != null && !gate.allows(externalId)) {
            return null;
        }
        String name = Placeholders.resolve(firstNonBlank(ctx.displayName, meta.displayName,
                ref.displayName, ref.fullName()), params);
        String title = Placeholders.resolve(firstNonBlank(ctx.title, meta.title), params);
        String description = Placeholders.resolve(
                firstNonBlank(ctx.description, meta.description), params);
        String namespace = namespaceOf(ref, meta, params);
        String classname = classnameOf(ref, meta, params);

        List<String> labels = Placeholders.resolveAll(dedupStrings(meta.labels, ctx.labels), params);
        List<String> tags = Placeholders.resolveAll(dedupStrings(meta.tags, ctx.tags), params);
        List<Link> links = new ArrayList<>();
        for (Link l : meta.links) {
            links.add(new Link(Placeholders.resolve(l.url(), params), l.type(),
                    Placeholders.resolve(l.title(), params),
                    Placeholders.resolve(l.description(), params)));
        }
        links.addAll(ctx.links);
        List<Long> caseIds = dedupLongs(ctx.caseIds, attr.caseIds);

        // def steps: setup -> before, call -> step, teardown -> after
        List<Step> defSteps = new ArrayList<>();
        for (StepNode n : ctx.setupSteps) {
            defSteps.add(toDefStep(n, StepKind.BEFORE.wire()));
        }
        for (StepNode n : ctx.callSteps) {
            defSteps.add(toDefStep(n, StepKind.STEP.wire()));
        }
        for (StepNode n : ctx.teardownSteps) {
            defSteps.add(toDefStep(n, StepKind.AFTER.wire()));
        }

        AutotestDef def = new AutotestDef(externalId, name)
                .title(title)
                .description(description)
                .namespace(namespace)
                .classname(classname)
                // runner identity: DoQA builds its native selection filter from the method name,
                // which a custom display name hides.
                .runnerMethod(ref.methodName)
                .labels(labels)
                .tags(tags)
                .links(links)
                .steps(defSteps)
                .caseIds(caseIds)
                .allureId(attr.allureId);

        // ----- result -----
        String finalMessage = message;
        if (finalMessage == null && !ctx.messages.isEmpty()) {
            finalMessage = String.join("\n", ctx.messages);
        }

        List<StepResult> stepResults = new ArrayList<>();
        for (StepNode n : ctx.callSteps) {
            stepResults.add(toResultStep(n, uploader));
        }
        List<StepResult> setupResults = new ArrayList<>();
        for (StepNode n : ctx.setupSteps) {
            setupResults.add(toResultStep(n, uploader));
        }
        List<StepResult> teardownResults = new ArrayList<>();
        for (StepNode n : ctx.teardownSteps) {
            teardownResults.add(toResultStep(n, uploader));
        }

        List<Attachment> attachments = new ArrayList<>();
        for (AttachmentRef a : ctx.attachments) {
            String mid = upload(uploader, a);
            if (mid != null) {
                attachments.add(new Attachment(mid));
            }
        }

        Long durationMs = ctx.tEnd > 0 && ctx.tStart > 0 ? (ctx.tEnd - ctx.tStart) : null;

        AutotestResult result = new AutotestResult(externalId, outcome)
                .name(name)
                .startedOn(ctx.tStart > 0 ? ctx.tStart : null)
                .completedOn(ctx.tEnd > 0 ? ctx.tEnd : null)
                .durationMs(durationMs)
                .message(Limits.truncate(finalMessage, maxMessage(limits)))
                .traces(Limits.truncate(traces, maxTrace(limits)))
                .parameters(buildParameters(ctx, ref, limits))
                .stepResults(stepResults)
                .setupResults(setupResults)
                .teardownResults(teardownResults)
                .attachments(attachments)
                .links(new ArrayList<>(links))
                // annotation and runtime opt-in are additive: either one turns the flag on
                .createManualCase(meta.createManualCase || ctx.createManualCase);

        // exact test-class FQCN - ClassFixtures walks its enclosing chain for @BeforeAll/@AfterAll
        return new Built(def, result, ref == null ? null : ref.fullName(), attr.allureId,
                ref == null ? null : ref.fqcn, ref == null ? null : ref.methodKey());
    }

    // ------------------------------------------------------------------ steps
    private static Step toDefStep(StepNode node, String kind) {
        List<Step> children = new ArrayList<>();
        for (StepNode c : node.children) {
            children.add(toDefStep(c, null));  // kind only meaningful at top level
        }
        return new Step(node.title, node.description, kind, children);
    }

    public static StepResult toResultStep(StepNode node, AttachmentUploader uploader) {
        List<Attachment> atts = new ArrayList<>();
        for (AttachmentRef a : node.attachments) {
            String mid = upload(uploader, a);
            if (mid != null) {
                atts.add(new Attachment(mid));
            }
        }
        List<StepResult> children = new ArrayList<>();
        for (StepNode c : node.children) {
            children.add(toResultStep(c, uploader));
        }
        String outcome = node.outcome != null ? node.outcome : Outcome.PASSED.wire();
        StepResult step = new StepResult(node.title, outcome, node.durationMs,
                Limits.truncate(node.message, maxMessage(DoqaSession.currentConfig())), atts, children);
        if (node.startMillis > 0) {
            step.startedOn(node.startMillis);
            if (node.durationMs != null) {
                step.completedOn(node.startMillis + node.durationMs);
            }
        }
        return step;
    }

    /** Upload with per-ref memoization (class-fixture attachments merge into many results). */
    private static String upload(AttachmentUploader uploader, AttachmentRef ref) {
        if (ref.uploadedId != null) {
            return ref.uploadedId;
        }
        String mid = uploader == null ? null : uploader.upload(ref);
        if (mid != null) {
            ref.uploadedId = mid;
        }
        return mid;
    }

    // ------------------------------------------------------------------ parameters
    private static List<Parameter> buildParameters(RuntimeContext ctx, TestRef ref,
                                                   DoqaConfig limits) {
        int maxValue = maxParameter(limits);
        List<Parameter> params = new ArrayList<>();
        if (!ctx.invocationParameters.isEmpty()) {
            // named per-argument parameters captured by the framework extension
            for (Object[] pair : ctx.invocationParameters) {
                params.add(new Parameter(String.valueOf(pair[0]),
                        Limits.truncate(String.valueOf(pair[1]), maxValue)));
            }
        } else if (ref.parameterized && ref.displayName != null) {
            // fallback without the extension: parse "[1] a=b" from the display name
            Matcher m = PARAM_DISPLAY.matcher(ref.displayName);
            if (m.matches()) {
                String args = m.group(2);
                if (args != null && !args.trim().isEmpty()) {
                    params.add(new Parameter("arguments", Limits.truncate(args.trim(), maxValue)));
                }
            }
        }
        for (Object[] pair : ctx.parameters) {
            Object value = pair[1];
            params.add(new Parameter(String.valueOf(pair[0]),
                    value instanceof String ? Limits.truncate((String) value, maxValue) : value));
        }
        return params;
    }

    // ------------------------------------------------------------------ helpers
    private static int maxTrace(DoqaConfig limits) {
        return limits != null ? limits.maxTraceLength() : DoqaConfig.DEFAULT_MAX_TRACE_LENGTH;
    }

    private static int maxMessage(DoqaConfig limits) {
        return limits != null ? limits.maxMessageLength() : DoqaConfig.DEFAULT_MAX_MESSAGE_LENGTH;
    }

    private static int maxParameter(DoqaConfig limits) {
        return limits != null ? limits.maxParameterLength() : DoqaConfig.DEFAULT_MAX_PARAMETER_LENGTH;
    }

    /** Namespace an autotest is reported under: {@code @DoqaNamespace} if declared, else the package. */
    public static String namespaceOf(TestRef ref, Meta meta, Map<String, String> params) {
        return Placeholders.resolve(firstNonBlank(meta.namespace, ref.packageName()),
                params == null ? Collections.<String, String>emptyMap() : params);
    }

    /** Classname an autotest is reported under: {@code @DoqaClassname} if declared, else the class. */
    public static String classnameOf(TestRef ref, Meta meta, Map<String, String> params) {
        return Placeholders.resolve(firstNonBlank(meta.classname, ref.simpleClassName()),
                params == null ? Collections.<String, String>emptyMap() : params);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    @SafeVarargs
    private static List<String> dedupStrings(List<String>... lists) {
        Set<String> seen = new LinkedHashSet<>();
        for (List<String> list : lists) {
            if (list != null) {
                seen.addAll(list);
            }
        }
        return new ArrayList<>(seen);
    }

    private static List<Long> dedupLongs(List<Long> a, long[] b) {
        Set<Long> seen = new LinkedHashSet<>();
        if (a != null) {
            seen.addAll(a);
        }
        if (b != null) {
            for (long v : b) {
                seen.add(v);
            }
        }
        return new ArrayList<>(seen);
    }
}
