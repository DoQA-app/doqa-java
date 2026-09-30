package app.doqa.cucumber;

import app.doqa.core.Outcomes;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;

/** A Cucumber {@link Result} as a DoQA outcome; one mapping for steps and scenarios. */
final class Outcome {

    static final String PASSED = "passed";
    static final String FAILED = "failed";
    static final String BROKEN = "broken";
    static final String SKIPPED = "skipped";

    final String outcome;
    final String message;
    final String traces;

    private Outcome(String outcome, String message, String traces) {
        this.outcome = outcome;
        this.message = message;
        this.traces = traces;
    }

    static Outcome of(Result result, String stepText) {
        Status status = result == null ? Status.PASSED : result.getStatus();
        Throwable error = result == null ? null : result.getError();
        String traces = Outcomes.stackTrace(error);
        switch (status) {
            case PASSED:
                return new Outcome(PASSED, null, null);
            case FAILED:
                return new Outcome(Outcomes.failureOutcome(error), Outcomes.messageOf(error), traces);
            case PENDING:
                return new Outcome(SKIPPED, "The step is not implemented yet (pending)"
                        + (error != null ? ": " + Outcomes.messageOf(error) : ""), traces);
            case UNDEFINED:
                return new Outcome(BROKEN, "No step definition matches the step"
                        + (stepText != null ? " \"" + stepText + "\"" : ""), null);
            case AMBIGUOUS:
                return new Outcome(BROKEN, error != null ? Outcomes.messageOf(error)
                        : "Several step definitions match the step", traces);
            case SKIPPED:
            case UNUSED:
            default:
                return new Outcome(SKIPPED, error != null ? Outcomes.messageOf(error) : null, traces);
        }
    }
}
