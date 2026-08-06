package app.doqa.core;

import app.doqa.client.Link;
import java.util.ArrayList;
import java.util.List;

/**
 * Annotation-derived metadata for one test (from method + class).
 *
 * <p>Internal adapter API.
 */
public final class Meta {
    public String displayName;
    public String title;
    public String description;
    public String namespace;
    public String classname;
    public boolean createManualCase;
    public final List<String> labels = new ArrayList<>();
    public final List<String> tags = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
}
