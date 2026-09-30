package app.doqa.cucumber;

import app.doqa.core.AttachmentRef;
import app.doqa.core.RuntimeContext;
import app.doqa.core.StepNode;
import io.cucumber.plugin.event.DataTableArgument;
import io.cucumber.plugin.event.DocStringArgument;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.HookType;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.Step;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestStep;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One scenario execution: turns step and hook events into the step tree of its context. */
final class ScenarioRun {

    final RuntimeContext ctx;
    final ScenarioModel model;
    final FeatureFile file;

    private StepNode open;
    private HookType openHook;
    private StepNode lastStep;
    private final List<AttachmentRef> pendingAttachments = new ArrayList<>();
    private final List<String> pendingMessages = new ArrayList<>();
    String firstUndefinedStep;

    ScenarioRun(RuntimeContext ctx, ScenarioModel model, FeatureFile file) {
        this.ctx = ctx;
        this.model = model;
        this.file = file;
    }

    void stepStarted(TestStep step, long startMillis) {
        if (step instanceof PickleStepTestStep) {
            ctx.phase = RuntimeContext.Phase.CALL;
            openStep(gherkinStep((PickleStepTestStep) step), startMillis);
            open.attachments.addAll(pendingAttachments);
            pendingAttachments.clear();
            for (String message : pendingMessages) {
                appendMessage(open, message);
            }
            pendingMessages.clear();
            return;
        }
        if (!(step instanceof HookTestStep)) {
            return;
        }
        HookType type = ((HookTestStep) step).getHookType();
        openHook = type;
        if (type == HookType.AFTER_STEP && lastStep != null) {
            // Doqa.step / Doqa.addAttachment in the hook land in the step it runs after
            ctx.stepStack.clear();
            ctx.stepStack.push(lastStep);
        }
        if (type == HookType.BEFORE || type == HookType.AFTER) {
            ctx.phase = type == HookType.BEFORE
                    ? RuntimeContext.Phase.SETUP : RuntimeContext.Phase.TEARDOWN;
            openStep(new StepNode(hookName(step.getCodeLocation())), startMillis);
        }
    }

    void stepFinished(TestStep step, Result result) {
        if (step instanceof PickleStepTestStep) {
            StepNode node = open;
            closeOpen();
            if (node == null) {
                return;
            }
            Outcome outcome = Outcome.of(result, node.title);
            node.outcome = outcome.outcome;
            appendMessage(node, outcome.message);
            if (result != null && result.getStatus() == Status.UNDEFINED
                    && firstUndefinedStep == null) {
                firstUndefinedStep = node.title;
            }
            setDuration(node, result);
            lastStep = node;
            return;
        }
        if (!(step instanceof HookTestStep)) {
            return;
        }
        HookType type = ((HookTestStep) step).getHookType();
        openHook = null;
        if (type == HookType.AFTER_STEP) {
            ctx.stepStack.clear();
        }
        Outcome outcome = Outcome.of(result, null);
        if (type == HookType.BEFORE || type == HookType.AFTER) {
            StepNode node = open;
            closeOpen();
            if (node != null) {
                node.outcome = outcome.outcome;
                appendMessage(node, outcome.message);
                setDuration(node, result);
            }
            return;
        }
        if (Outcome.PASSED.equals(outcome.outcome) || outcome.message == null
                && Outcome.SKIPPED.equals(outcome.outcome)) {
            return;
        }
        String hook = "@" + (type == HookType.BEFORE_STEP ? "BeforeStep" : "AfterStep") + " "
                + hookName(step.getCodeLocation()) + " " + outcome.outcome
                + (outcome.message != null ? ": " + outcome.message : "");
        if (type == HookType.BEFORE_STEP) {
            pendingMessages.add(hook);
        } else if (lastStep != null) {
            if (!Outcome.SKIPPED.equals(outcome.outcome)) {
                lastStep.outcome = outcome.outcome;
            }
            appendMessage(lastStep, hook);
        }
    }

    void attach(AttachmentRef attachment) {
        if (open != null) {
            open.attachments.add(attachment);
        } else if (openHook == HookType.BEFORE_STEP) {
            pendingAttachments.add(attachment);
        } else if (openHook == HookType.AFTER_STEP && lastStep != null) {
            lastStep.attachments.add(attachment);
        } else {
            ctx.attachments.add(attachment);
        }
    }

    void log(String text) {
        if (text == null) {
            return;
        }
        if (open != null) {
            appendMessage(open, text);
        } else if (openHook == HookType.BEFORE_STEP) {
            pendingMessages.add(text);
        } else if (openHook == HookType.AFTER_STEP && lastStep != null) {
            appendMessage(lastStep, text);
        } else {
            ctx.messages.add(text);
        }
    }

    void finish() {
        while (!ctx.stepStack.isEmpty()) {
            StepNode node = ctx.stepStack.pop();
            if (node.outcome == null) {
                node.outcome = Outcome.SKIPPED;
            }
        }
        open = null;
    }

    private StepNode gherkinStep(PickleStepTestStep testStep) {
        Step step = testStep.getStep();
        String keyword = step.getKeyword() == null ? "" : step.getKeyword().trim();
        StepNode node = new StepNode(title(keyword, step.getText()));
        if (model.outline && file != null) {
            String template = file.stepText(step.getLine(), keyword);
            if (template != null) {
                node.definitionTitle = title(keyword, template);
            }
        }
        StepArgument argument = step.getArgument();
        if (argument instanceof DocStringArgument) {
            String content = ((DocStringArgument) argument).getContent();
            node.attachments.add(text("DocString", content));
        } else if (argument instanceof DataTableArgument) {
            node.attachments.add(text("DataTable", table(((DataTableArgument) argument).cells())));
        }
        return node;
    }

    private void openStep(StepNode node, long startMillis) {
        closeOpen();
        if (startMillis > 0) {
            node.startMillis = startMillis;
        }
        ctx.phaseBucket().add(node);
        ctx.stepStack.clear();
        ctx.stepStack.push(node);
        open = node;
    }

    private void closeOpen() {
        // a Doqa.step left open by the step code must not swallow the next Gherkin step
        ctx.stepStack.clear();
        open = null;
    }

    private static void setDuration(StepNode node, Result result) {
        if (result != null && result.getDuration() != null) {
            node.durationMs = result.getDuration().toMillis();
        } else {
            node.durationMs = System.currentTimeMillis() - node.startMillis;
        }
    }

    private static void appendMessage(StepNode node, String message) {
        if (message != null) {
            node.message = node.message == null ? message : node.message + "\n" + message;
        }
    }

    private static String title(String keyword, String text) {
        String t = text == null ? "" : text;
        return keyword.isEmpty() ? t : keyword + " " + t;
    }

    static String hookName(String codeLocation) {
        if (codeLocation == null || codeLocation.trim().isEmpty()) {
            return "hook";
        }
        String location = codeLocation.trim();
        int paren = location.indexOf('(');
        String qualified = paren > 0 ? location.substring(0, paren) : location;
        int method = qualified.lastIndexOf('.');
        if (method <= 0) {
            return qualified;
        }
        int type = qualified.lastIndexOf('.', method - 1);
        return qualified.substring(type + 1);
    }

    private static String table(List<List<String>> cells) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : cells) {
            out.append('|');
            for (String cell : row) {
                out.append(' ').append(cell == null ? "" : cell.replace("|", "\\|")).append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static AttachmentRef text(String name, String content) {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        return AttachmentRef.ofBytes(name, bytes, "text/plain");
    }
}
