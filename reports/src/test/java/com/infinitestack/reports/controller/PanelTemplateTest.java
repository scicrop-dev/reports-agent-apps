package com.infinitestack.reports.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The panel is a Thymeleaf file, and Thymeleaf reads two sequences inside text as the opening of
 * an inline expression: bracket-bracket and bracket-parenthesis. When one shows up by accident the
 * page dies while rendering, with a generic 500 through the host's proxy. This test makes the
 * problem show up in the build, with the exact line. Real inlining (followed by ${...}) is allowed.
 */
class PanelTemplateTest {

    private static final Pattern INLINE_OPENING = Pattern.compile("\\[[\\[(]");

    @Test
    void noAccidentalInlineExpression() throws IOException {
        String template = read("templates/isp-index.html");
        List<String> offenders = new ArrayList<>();
        Matcher matcher = INLINE_OPENING.matcher(template);
        while (matcher.find()) {
            if (!template.startsWith("${", matcher.end())) {
                offenders.add("line " + lineOf(template, matcher.start()) + ": "
                        + template.substring(matcher.start(), Math.min(matcher.start() + 60, template.length())).replace('\n', ' '));
            }
        }
        assertEquals(List.of(), offenders, "sequence Thymeleaf reads as an inline expression; the page breaks");
    }

    @Test
    void packageLayoutTemplatesMatchTheSource() throws IOException {
        for (String name : List.of("isp-index.html", "isp-status.html")) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates", name));
            String layout = java.nio.file.Files.readString(java.nio.file.Path.of("package-layout/reports/web/templates", name));
            assertEquals(source, layout, name + " differs between src/ and package-layout/; run step2");
        }
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = PanelTemplateTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("resource not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
