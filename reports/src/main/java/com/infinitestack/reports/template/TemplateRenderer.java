package com.infinitestack.reports.template;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.config.ReportsLimits;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;

/**
 * Turns a template plus an execution context into report HTML (ARCHITECTURE section 15).
 *
 * <p>Templates are logic-less Mustache: {@code {{namespace.field}}}, sections to iterate arrays
 * and test values, nothing else. Values are HTML-escaped. Rendering is strict: a variable that
 * does not exist fails instead of printing nothing, so a typo cannot ship a silently empty report
 * (MASTER section 13). Partials are not supported. The result always goes through the
 * {@link HtmlSanitizer}, whatever the template contains.
 */
@Component
public class TemplateRenderer {

    private static final Set<String> SECTION_ONLY_NAMES = Set.of(".", "this", "-first", "-last", "-index");

    private final Mustache.Compiler compiler = Mustache.compiler()
            .strictSections(true)
            .nullValue("");
    private final HtmlSanitizer sanitizer;
    private final ReportsLimits limits;

    public TemplateRenderer(HtmlSanitizer sanitizer, ReportsLimits limits) {
        this.sanitizer = sanitizer;
        this.limits = limits;
    }

    /**
     * Checks a template before running anything: syntax, size, and that every variable outside a
     * section exists in the scope. Variables inside sections are relative to the section's value,
     * which is only known at run time; strict rendering catches those.
     */
    public List<String> validate(String html, String css, TemplateScope scope) {
        List<String> problems = new ArrayList<>(checkSize(html, css));
        Template template;
        try {
            template = compiler.compile(html == null ? "" : html);
        } catch (MustacheException e) {
            problems.add("template syntax: " + e.getMessage());
            return problems;
        }
        Set<String> seen = new LinkedHashSet<>();
        template.visit(new Mustache.Visitor() {
            @Override public void visitText(String text) { }

            @Override public void visitVariable(String name) {
                if (seen.add("v:" + name)) checkPath(name, false, scope).ifPresent(problems::add);
            }

            @Override public boolean visitInclude(String name) {
                problems.add("partials are not supported ({{> " + name + "}})");
                return false;
            }

            @Override public boolean visitSection(String name) {
                if (seen.add("s:" + name)) checkPath(name, true, scope).ifPresent(problems::add);
                return false;
            }

            @Override public boolean visitInvertedSection(String name) {
                return visitSection(name);
            }
        });
        return problems;
    }

    /** Renders and sanitizes; returns the complete HTML document. */
    public String render(String title, String html, String css, Map<String, Object> model) throws TemplateException {
        List<String> sizeProblems = checkSize(html, css);
        if (!sizeProblems.isEmpty()) throw new TemplateException(sizeProblems);
        String body;
        try {
            body = compiler.compile(html == null ? "" : html).execute(model);
        } catch (MustacheException e) {
            throw new TemplateException(List.of(e.getMessage()));
        }
        String document = sanitizer.document(title, sanitizer.sanitizeBody(body), sanitizer.sanitizeCss(css));
        if (document.getBytes(StandardCharsets.UTF_8).length > limits.maxRenderedBytes()) {
            throw new TemplateException(List.of("the rendered report exceeds " + limits.maxRenderedBytes() + " bytes"));
        }
        return document;
    }

    private List<String> checkSize(String html, String css) {
        int size = (html == null ? 0 : html.getBytes(StandardCharsets.UTF_8).length)
                + (css == null ? 0 : css.getBytes(StandardCharsets.UTF_8).length);
        return size > limits.maxTemplateBytes()
                ? List.of("the template has " + size + " bytes; the limit is " + limits.maxTemplateBytes())
                : List.of();
    }

    /** {@code namespace.port[.inner...]}: inner fields only below object, array or geometry ports. */
    static Optional<String> checkPath(String name, boolean section, TemplateScope scope) {
        String label = section ? "section {{#" + name + "}}" : "variable {{" + name + "}}";
        if (SECTION_ONLY_NAMES.contains(name)) {
            return Optional.of(label + " only makes sense inside a section");
        }
        String[] parts = name.split("\\.", -1);
        if (!scope.hasNamespace(parts[0])) {
            return Optional.of(label + ": unknown namespace '" + parts[0] + "'");
        }
        if (parts.length == 1) {
            return section ? Optional.empty()
                    : Optional.of(label + ": name a field, e.g. {{" + parts[0] + ".something}}");
        }
        Optional<TemplateScope.Field> field = scope.field(parts[0], parts[1]);
        if (field.isEmpty()) {
            return Optional.of(label + ": '" + parts[0] + "' has no field '" + parts[1] + "'");
        }
        if (parts.length > 2 && !field.get().type().isStructured()) {
            return Optional.of(label + ": " + parts[0] + "." + parts[1] + " is a " + field.get().type().json()
                    + " and has no inner fields");
        }
        return Optional.empty();
    }
}
