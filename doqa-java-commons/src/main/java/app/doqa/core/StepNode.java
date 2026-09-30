package app.doqa.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One node in the executed step tree, carrying the def facet (title/description) and the result
 * facet (outcome/duration/message/attachments); {@link ResultBuilder} projects it into a
 * client-core {@code Step} (def) and {@code StepResult} (result).
 *
 * <p>Internal adapter API.
 */
public final class StepNode {

    public String title;
    /** Title in the autotest definition when it differs from the executed one (a template). */
    public String definitionTitle;
    public String description;
    public String outcome;       // passed | failed | skipped | broken
    public String message;
    public Long durationMs;
    public final List<AttachmentRef> attachments = new ArrayList<>();
    public final List<StepNode> children = new ArrayList<>();
    public long startMillis;

    public StepNode(String title) {
        this.title = title;
        this.startMillis = System.currentTimeMillis();
    }
}
