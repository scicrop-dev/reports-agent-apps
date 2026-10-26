package com.infinitestack.reports.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ReadyMadeCatalogTest {

    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    void everyShippedReadyMadeLoadsWithAUniqueIdAndASemverVersion() {
        ReadyMadeCatalog catalog = new ReadyMadeCatalog();
        assertFalse(catalog.all().isEmpty());
        Set<String> ids = new HashSet<>();
        for (ReadyMadeReport report : catalog.all()) {
            assertTrue(ids.add(report.id()), "duplicate id " + report.id());
            assertTrue(SEMVER.matcher(report.version()).matches(), report.id() + ": " + report.version());
            assertFalse(report.templateHtml().isBlank(), report.id() + " has no template");
            assertFalse(report.requiredBlocks().isEmpty(), report.id() + " uses no block");
        }
        assertTrue(catalog.find("car-socioenvironmental").isPresent());
    }

    @Test
    void readsTheTemplateFromASiblingFile() {
        Map<String, ReadyMadeReport> loaded = ReadyMadeCatalog.load(resolver, "ready-made-fixtures/valid/");
        ReadyMadeReport report = loaded.get("valid-one");
        assertEquals("<p>{{upper.text}}</p>\n", report.templateHtml());
        assertEquals("", report.templateCss());
        assertEquals("A4", report.page().size());
        assertEquals(java.util.List.of("test-upper"), report.requiredBlocks());
    }

    @Test
    void failsFastOnAMalformedFile() {
        IllegalStateException version = assertThrows(IllegalStateException.class,
                () -> ReadyMadeCatalog.load(resolver, "ready-made-fixtures/bad-version/"));
        assertTrue(version.getMessage().contains("semver"), version.getMessage());
        IllegalStateException typo = assertThrows(IllegalStateException.class,
                () -> ReadyMadeCatalog.load(resolver, "ready-made-fixtures/unknown-field/"));
        assertTrue(typo.getMessage().contains("templateHtlm"), typo.getMessage());
    }

    @Test
    void failsFastOnADuplicateId() {
        // The same file seen twice, as when two jars on the classpath ship the same id.
        PathMatchingResourcePatternResolver twice = new PathMatchingResourcePatternResolver() {
            @Override public Resource[] getResources(String pattern) throws IOException {
                Resource[] once = super.getResources(pattern);
                Resource[] out = new Resource[once.length * 2];
                System.arraycopy(once, 0, out, 0, once.length);
                System.arraycopy(once, 0, out, once.length, once.length);
                return out;
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ReadyMadeCatalog.load(twice, "ready-made-fixtures/valid/"));
        assertTrue(e.getMessage().contains("Duplicate"), e.getMessage());
    }
}
