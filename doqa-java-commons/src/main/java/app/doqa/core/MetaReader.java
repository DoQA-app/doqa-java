package app.doqa.core;

import app.doqa.annotations.DoqaClassName;
import app.doqa.annotations.DoqaCreateManualCase;
import app.doqa.annotations.DoqaDescription;
import app.doqa.annotations.DoqaDisplayName;
import app.doqa.annotations.DoqaLabels;
import app.doqa.annotations.DoqaLink;
import app.doqa.annotations.DoqaNamespace;
import app.doqa.annotations.DoqaTags;
import app.doqa.annotations.DoqaTitle;
import app.doqa.client.Link;
import app.doqa.client.LinkType;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Reads all the {@code @Doqa*} attribution annotations off the test method + class into a
 * {@link Meta} (scalars: method wins over class; accumulating labels/tags/links: class then method
 * merged), then lets {@link AllureCompat} fill the gaps from native Allure annotations.
 * {@code @DoqaId} / {@code @DoqaCaseIds} are handled by {@link Attribution}.
 *
 * <p>Internal adapter API.
 */
public final class MetaReader {

    private static final Logger LOG = Logger.getLogger(MetaReader.class.getName());
    /** Unknown @DoqaLink types warned once per value - a typo floods neither log nor wire. */
    private static final Set<String> WARNED_LINK_TYPES = ConcurrentHashMap.newKeySet();

    private MetaReader() {
    }

    public static Meta read(TestRef ref) {
        Meta meta = new Meta();
        // class first (so method overrides scalars), then method.
        collect(meta, ref.testClass);
        collect(meta, ref.testMethod);
        AllureCompat.fill(meta, ref.testClass);
        AllureCompat.fill(meta, ref.testMethod);
        return meta;
    }

    private static void collect(Meta meta, AnnotatedElement el) {
        if (el == null) {
            return;
        }
        DoqaDisplayName dn = el.getAnnotation(DoqaDisplayName.class);
        if (dn != null) {
            meta.displayName = dn.value();
        }
        DoqaTitle t = el.getAnnotation(DoqaTitle.class);
        if (t != null) {
            meta.title = t.value();
        }
        DoqaDescription d = el.getAnnotation(DoqaDescription.class);
        if (d != null) {
            meta.description = d.value();
        }
        DoqaNamespace ns = el.getAnnotation(DoqaNamespace.class);
        if (ns != null) {
            meta.namespace = ns.value();
        }
        DoqaClassName cn = el.getAnnotation(DoqaClassName.class);
        if (cn != null) {
            meta.classname = cn.value();
        }
        if (el.isAnnotationPresent(DoqaCreateManualCase.class)) {
            meta.createManualCase = true;
        }
        DoqaLabels labels = el.getAnnotation(DoqaLabels.class);
        if (labels != null) {
            meta.labels.addAll(Arrays.asList(labels.value()));
        }
        DoqaTags tags = el.getAnnotation(DoqaTags.class);
        if (tags != null) {
            meta.tags.addAll(Arrays.asList(tags.value()));
        }
        // getAnnotationsByType flattens both a single/repeated @DoqaLink and the @DoqaLinks
        // container, so lone @DoqaLink annotations are no longer silently ignored.
        for (DoqaLink l : el.getAnnotationsByType(DoqaLink.class)) {
            meta.links.add(toLink(l));
        }
    }

    private static Link toLink(DoqaLink l) {
        String rawType = l.type() == null || l.type().isEmpty() ? null : l.type();
        LinkType parsed = LinkType.from(rawType);
        if (rawType != null && parsed == null && WARNED_LINK_TYPES.add(rawType)) {
            LOG.warning("DoQA: unknown @DoqaLink type \"" + rawType
                    + "\" - link kept without a type (valid: related|defect|requirement|blocked_by|repository)");
        }
        String title = l.title() == null || l.title().isEmpty() ? null : l.title();
        String desc = l.description() == null || l.description().isEmpty() ? null : l.description();
        return new Link(l.url(), parsed == null ? null : parsed.wire(), title, desc);
    }
}
