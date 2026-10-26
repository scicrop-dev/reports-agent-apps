package com.infinitestack.reports.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.support.TestBlocks;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer(new HtmlSanitizer(), ReportsLimits.defaults());
    private final TemplateScope scope = TemplateScope.of(
            List.of(new InputDefinition("name", DataType.STRING, true, "Name")),
            new FlowDefinition(List.of(new FlowNode("area", "test-area", Map.of())), List.of()),
            new BlockRegistry(TestBlocks.all()));

    /** Renders with the default page settings. */
    private String render(String html, String css, java.util.Map<String, Object> model) throws TemplateException {
        return renderer.render("R", html, css, null, model);
    }

    @Test
    void rendersNamespacedVariablesEscaped() throws Exception {
        String html = render( "<h1>{{inputs.name}}</h1><p>{{area.details.name}}</p>", "h1 { color: red; }",
                Map.of("inputs", Map.of("name", "<b>Fazenda</b> & cia"), "area", Map.of("details", Map.of("name", "Garça"))));
        assertTrue(html.contains("<h1>&lt;b&gt;Fazenda&lt;/b&gt; &amp; cia</h1>"), html);
        assertTrue(html.contains("<p>Garça</p>"), html);
        assertTrue(html.contains("h1 { color: red; }"), html);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
    }

    @Test
    void validationListsEveryUnknownVariable() {
        List<String> problems = renderer.validate(
                "{{inputs.name}} {{inputs.nope}} {{other.x}} {{area.code.deeper}} {{area.details.anything}} {{#area.details}}{{whatever}}{{/area.details}}",
                "", scope);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("no field 'nope'"));
        assertTrue(problems.get(1).contains("unknown namespace 'other'"));
        assertTrue(problems.get(2).contains("has no inner fields"));
    }

    @Test
    void validationRejectsPartialsAndBadSyntax() {
        assertTrue(renderer.validate("{{> header}}", "", scope).get(0).contains("partials"));
        assertTrue(renderer.validate("{{#inputs.name}}no close", "", scope).get(0).contains("syntax"));
    }

    @Test
    void aMissingValueAtRunTimeFailsInsteadOfPrintingNothing() {
        TemplateException e = assertThrows(TemplateException.class,
                () -> render( "{{inputs.name}}", "", Map.of("inputs", Map.of())));
        assertFalse(e.problems().isEmpty());
    }

    @Test
    void aNullValuePrintsEmpty() throws Exception {
        java.util.HashMap<String, Object> inputs = new java.util.HashMap<>();
        inputs.put("name", null);
        assertTrue(render( "[{{inputs.name}}]", "", Map.of("inputs", inputs)).contains("[]"));
    }

    @Test
    void sanitizesScriptsHandlersAndExternalResources() throws Exception {
        String html = render(
                "<script>alert(1)</script><p onclick=\"x()\" style=\"background:url(http://evil/x.png);color:red\">ok</p>"
                        + "<img src=\"https://evil/x.png\"><img src=\"data:image/png;base64,AAAA\"><iframe src=\"/x\"></iframe>"
                        + "<a href=\"javascript:alert(1)\">j</a><a href=\"https://scicrop.com\">s</a>",
                "@import url(http://evil/a.css); body { background: url('https://evil/b.png'); } </style><script>",
                Map.of());
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("onclick"), html);
        assertFalse(html.contains("evil"), html);
        assertFalse(html.contains("<iframe"), html);
        assertFalse(html.contains("javascript:"), html);
        assertTrue(html.contains("data:image/png;base64,AAAA"), html);
        assertTrue(html.contains("href=\"https://scicrop.com\""), html);
        assertTrue(html.contains("color:red"), html);
    }

    @Test
    void enforcesTheTemplateSize() {
        TemplateRenderer small = new TemplateRenderer(new HtmlSanitizer(), new ReportsLimits(50, 30, 120, 10, 1000, 1000, 60));
        assertTrue(small.validate("01234567890", "", scope).get(0).contains("limit"));
    }

    @Test
    void variablesAreOfferedFromInputsAndBlockOutputs() {
        assertEquals(List.of("inputs.name", "area.code", "area.details"),
                scope.variables().stream().map(TemplateScope.Variable::path).toList());
    }
}
