package app.doqa.core;

import app.doqa.client.Link;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Everything collected for one test across setup / call / teardown. Steps are bucketed by phase;
 * the {@code stepStack} tracks the currently open (nested) steps so {@code Doqa.step}, the AspectJ
 * aspect and fixtures all nest correctly.
 *
 * <p>Internal adapter API.
 */
public final class RuntimeContext {

    public enum Phase { SETUP, CALL, TEARDOWN }

    /** Registry key; null for transient (fixture / hand-bound) contexts. */
    public final String uniqueId;
    public TestRef testRef;
    /** Set by {@link DoqaContexts#remove}: a closed context silently rejects late writes. */
    volatile boolean closed;

    // runtime add* overrides / additions
    public String externalId;
    public String title;
    public String displayName;
    public String description;
    /** Runtime opt-in; ORed with the {@code @DoqaCreateManualCase} marker when the result is built. */
    public boolean createManualCase;
    public final List<String> labels = new ArrayList<>();
    public final List<String> tags = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    public final List<Long> caseIds = new ArrayList<>();
    public final List<Object[]> parameters = new ArrayList<>();   // [name, value] - runtime addParameter
    // [name, stringifiedValue] - parameterized-invocation args captured by the framework adapter
    // (real names need the host compiled with -parameters, else arg0..argN).
    public final List<Object[]> invocationParameters = new ArrayList<>();
    public final List<AttachmentRef> attachments = new ArrayList<>();
    public final List<String> messages = new ArrayList<>();

    // executed steps, bucketed by phase
    public final List<StepNode> setupSteps = new ArrayList<>();
    public final List<StepNode> callSteps = new ArrayList<>();
    public final List<StepNode> teardownSteps = new ArrayList<>();
    public final Deque<StepNode> stepStack = new ArrayDeque<>();

    // Default CALL so that when no phase-managing extension is active (AspectJ-only or
    // explicit-step setups), test-body steps still land in the call bucket. The framework
    // adapter flips this to SETUP/TEARDOWN around per-test fixtures and back to CALL for the
    // test method.
    public Phase phase = Phase.CALL;

    // timing / outcome (from the framework's execution result)
    public long tStart;
    public long tEnd;

    public RuntimeContext(String uniqueId) {
        this.uniqueId = uniqueId;
        this.tStart = System.currentTimeMillis();
    }

    public List<StepNode> phaseBucket() {
        switch (phase) {
            case CALL: return callSteps;
            case TEARDOWN: return teardownSteps;
            default: return setupSteps;
        }
    }
}
