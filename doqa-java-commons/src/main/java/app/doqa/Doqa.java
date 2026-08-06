package app.doqa;

import app.doqa.client.Link;
import app.doqa.client.LinkType;
import app.doqa.core.AttachmentRef;
import app.doqa.core.DoqaContexts;
import app.doqa.core.Outcomes;
import app.doqa.core.RuntimeContext;
import app.doqa.core.StepNode;
import app.doqa.core.Steps;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Public runtime facade used inside test bodies. All calls are no-ops when there is no active test
 * context, so using them without the adapter never throws.
 *
 * <pre>{@code
 * Doqa.addParameter("browser", "chrome");
 * Doqa.addLink("http://bug/1", LinkType.DEFECT);
 * Doqa.step("open page", () -> page.open());
 * int total = Doqa.step("sum", () -> a + b);
 * Doqa.addAttachment("response.json", jsonBytes, "application/json");
 * }</pre>
 *
 * <p>Steps and attachments recorded from a thread the test spawned need the context carried
 * over explicitly: {@code Doqa.runWith(Doqa.captureContext(), () -> ...)}.
 */
public final class Doqa {

    private Doqa() {
    }

    /** A body that may throw (for {@link #step(String, ThrowingRunnable)}). */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /** A value-returning body that may throw (for {@link #step(String, ThrowingSupplier)}). */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /** Opaque handle for carrying the current test context into user-spawned threads. */
    public static final class Context {
        private final RuntimeContext ctx;

        Context(RuntimeContext ctx) {
            this.ctx = ctx;
        }
    }

    // --- steps ---------------------------------------------------------------
    /** Run {@code body} as a named step (nested, timed). Failures mark the step broken/failed and rethrow. */
    public static void step(String title, ThrowingRunnable body) {
        step(title, null, body);
    }

    /** Run {@code body} as a named step with a definition-level description. */
    public static void step(String title, String description, ThrowingRunnable body) {
        step(title, description, () -> {
            body.run();
            return null;
        });
    }

    /** Run {@code body} as a named step returning its value. */
    public static <T> T step(String title, ThrowingSupplier<T> body) {
        return step(title, null, body);
    }

    /** Run {@code body} as a named step (with description) returning its value. */
    public static <T> T step(String title, String description, ThrowingSupplier<T> body) {
        Steps.push(title, description);
        try {
            T value = body.get();
            Steps.pop("passed", null);
            return value;
        } catch (Throwable t) {
            Steps.pop(Outcomes.failureOutcome(t), Outcomes.messageOf(t));
            sneakyThrow(t);
            return null; // unreachable
        }
    }

    /** Record an instant passed checkpoint step (no body). */
    public static void step(String title) {
        Steps.push(title);
        Steps.pop("passed", null);
    }

    // --- runtime add* --------------------------------------------------------
    /**
     * Pin the autotest {@code externalId} for the CURRENT invocation - wins over any
     * annotation-derived id. The only way dynamic ({@code @TestFactory}-style) tests can carry a
     * stable explicit id; renaming the dynamic test no longer breaks its history. NB: in
     * selective mode the id participates in report-time gating, not in discovery deselection.
     */
    public static void addExternalId(String externalId) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.externalId = externalId;
        }
    }

    public static void addLink(String url, LinkType type) {
        addLinkInternal(new Link(url, type, null, null));
    }

    public static void addLink(String url, LinkType type, String title, String description) {
        addLinkInternal(new Link(url, type, title, description));
    }

    public static void addLinks(Link... links) {
        for (Link l : links) {
            addLinkInternal(l);
        }
    }

    private static void addLinkInternal(Link link) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null && link != null) {
            ctx.links.add(link);
        }
    }

    /** Attach a message to the innermost open step, else to the test. */
    public static void addMessage(String message) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx == null || message == null) {
            return;
        }
        if (!ctx.stepStack.isEmpty()) {
            StepNode node = ctx.stepStack.peek();
            node.message = node.message == null ? message : node.message + "\n" + message;
        } else {
            ctx.messages.add(message);
        }
    }

    public static void addAttachments(String... paths) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx == null) {
            return;
        }
        List<AttachmentRef> target = attachmentTarget(ctx);
        for (String p : paths) {
            if (p != null) {
                target.add(AttachmentRef.ofPath(p));
            }
        }
    }

    /** Attach in-memory content (no temp file): screenshots, API responses, generated logs. */
    public static void addAttachment(String name, byte[] content, String contentType) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            attachmentTarget(ctx).add(AttachmentRef.ofBytes(name, content, contentType));
        }
    }

    /** Attach text content as {@code text/plain} (UTF-8). */
    public static void addAttachment(String name, String content) {
        addAttachment(name, content == null ? null : content.getBytes(StandardCharsets.UTF_8),
                "text/plain");
    }

    private static List<AttachmentRef> attachmentTarget(RuntimeContext ctx) {
        return !ctx.stepStack.isEmpty() ? ctx.stepStack.peek().attachments : ctx.attachments;
    }

    public static void addParameter(String name, Object value) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.parameters.add(new Object[]{name, value});
        }
    }

    public static void addCaseIds(long... ids) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx == null) {
            return;
        }
        for (long id : ids) {
            ctx.caseIds.add(id);
        }
    }

    /**
     * Ask DoQA to create a linked manual test case for the CURRENT autotest - the runtime twin of
     * {@code @DoqaCreateManualCase}. Additive with the annotation: either source turns the flag
     * on, neither can turn it back off.
     */
    public static void addCreateManualCase() {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.createManualCase = true;
        }
    }

    public static void addTitle(String title) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.title = title;
        }
    }

    public static void addDescription(String description) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.description = description;
        }
    }

    public static void addDisplayName(String name) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.displayName = name;
        }
    }

    public static void addLabels(String... labels) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.labels.addAll(Arrays.asList(labels));
        }
    }

    /** Add one {@code key:value} label - see {@link Labels} for the well-known keys. */
    public static void addLabel(String name, String value) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null && name != null && value != null) {
            ctx.labels.add(Labels.of(name, value));
        }
    }

    public static void addTags(String... tags) {
        RuntimeContext ctx = DoqaContexts.current();
        if (ctx != null) {
            ctx.tags.addAll(Arrays.asList(tags));
        }
    }

    // --- context transfer ----------------------------------------------------
    /**
     * Capture the current test context for use in a thread the test spawns. Steps/attachments
     * recorded on other threads are otherwise dropped (thread-local scoping).
     */
    public static Context captureContext() {
        return new Context(DoqaContexts.current());
    }

    /** Run {@code body} with {@code context} bound to the calling thread, restoring the prior one. */
    public static void runWith(Context context, Runnable body) {
        RuntimeContext previous = DoqaContexts.push(context == null ? null : context.ctx);
        try {
            body.run();
        } finally {
            DoqaContexts.restore(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
