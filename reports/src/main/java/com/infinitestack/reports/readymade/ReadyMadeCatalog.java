package com.infinitestack.reports.readymade;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.report.PageSettings;

/**
 * Every ready-made report the app ships, read once from {@code classpath:ready-made/*.json} at
 * startup. A malformed file or a duplicate id stops the boot: a broken catalog is a packaging bug
 * and should never reach a user as a half-listed screen.
 *
 * <p>Big templates can live next to the JSON: {@code templateHtmlFile} / {@code templateCssFile}
 * name a sibling resource ({@code <id>.html}, {@code <id>.css}) instead of inlining the text.
 * Whether the Flow's Blocks exist is not checked here: they come and go with releases and the
 * catalog, and the screen says when one is missing.
 */
@Component
public class ReadyMadeCatalog {

    private static final Logger log = LoggerFactory.getLogger(ReadyMadeCatalog.class);

    public static final String LOCATION = "ready-made/";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern SEMVER = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
    private static final Pattern SIBLING = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");

    /** The file as written: like {@link ReadyMadeReport}, plus the optional sibling files. */
    record Source(String id, String version, String name, String description, String category,
                  List<InputDefinition> inputs, FlowDefinition flow, String templateHtml, String templateHtmlFile,
                  String templateCss, String templateCssFile, PageSettings page) {}

    private final Map<String, ReadyMadeReport> reports;

    public ReadyMadeCatalog() {
        this(new PathMatchingResourcePatternResolver(ReadyMadeCatalog.class.getClassLoader()));
    }

    ReadyMadeCatalog(ResourcePatternResolver resolver) {
        this.reports = load(resolver, LOCATION);
        log.info("[reports] {} ready-made report(s): {}", reports.size(), reports.keySet());
    }

    public Collection<ReadyMadeReport> all() {
        return reports.values();
    }

    public Optional<ReadyMadeReport> find(String id) {
        return Optional.ofNullable(id == null ? null : reports.get(id));
    }

    /** @param location a classpath folder ending in '/', e.g. {@link #LOCATION} */
    static Map<String, ReadyMadeReport> load(ResourcePatternResolver resolver, String location) {
        // Not the app's ObjectMapper bean: an unknown field in a shipped file is a typo, so fail on it.
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Resource[] files;
        try {
            files = resolver.getResources("classpath*:" + location + "*.json");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list the ready-made reports: " + e.getMessage(), e);
        }
        Arrays.sort(files, Comparator.comparing(r -> String.valueOf(r.getFilename())));
        Map<String, ReadyMadeReport> out = new LinkedHashMap<>();
        for (Resource file : files) {
            ReadyMadeReport report = read(file, mapper);
            if (out.putIfAbsent(report.id(), report) != null) {
                throw new IllegalStateException("Duplicate ready-made report id: " + report.id());
            }
        }
        return out;
    }

    private static ReadyMadeReport read(Resource file, ObjectMapper mapper) {
        String label = "ready-made report " + file.getFilename();
        Source source;
        try (InputStream in = file.getInputStream()) {
            source = mapper.readValue(in, Source.class);
        } catch (IOException e) {
            throw new IllegalStateException("Malformed " + label + ": " + e.getMessage(), e);
        }
        List<String> problems = check(source, file.getFilename());
        if (!problems.isEmpty()) throw new IllegalStateException("Invalid " + label + ": " + problems);
        String html = source.templateHtmlFile() == null ? source.templateHtml() : sibling(file, source.templateHtmlFile(), label);
        String css = source.templateCssFile() == null ? source.templateCss() : sibling(file, source.templateCssFile(), label);
        return new ReadyMadeReport(source.id(), source.version(), source.name(), source.description(), source.category(),
                source.inputs(), source.flow(), html, css, source.page());
    }

    static List<String> check(Source s, String filename) {
        List<String> problems = new ArrayList<>();
        if (s.id() == null || !ID.matcher(s.id()).matches()) problems.add("id must be kebab-case");
        else if (!(s.id() + ".json").equals(filename)) problems.add("the file must be named " + s.id() + ".json");
        if (s.version() == null || !SEMVER.matcher(s.version()).matches()) problems.add("version must be semver (x.y.z)");
        if (s.name() == null || s.name().isBlank()) problems.add("name is required");
        if (s.name() != null && s.name().length() > 200) problems.add("name has more than 200 characters");
        if (s.flow() == null || s.flow().nodes().isEmpty()) problems.add("flow has no node");
        else if (s.flow().nodes().stream().map(FlowNode::block).anyMatch(b -> b == null || b.isBlank())) {
            problems.add("every flow node names a block");
        }
        if (s.templateHtml() != null && s.templateHtmlFile() != null) problems.add("use templateHtml or templateHtmlFile, not both");
        if (s.templateCss() != null && s.templateCssFile() != null) problems.add("use templateCss or templateCssFile, not both");
        for (String sibling : new String[] {s.templateHtmlFile(), s.templateCssFile()}) {
            if (sibling != null && !SIBLING.matcher(sibling).matches()) problems.add("'" + sibling + "' must be a file next to the JSON");
        }
        if (s.page() != null) problems.addAll(s.page().problems());
        return problems;
    }

    private static String sibling(Resource file, String name, String label) {
        try {
            Resource other = file.createRelative(name);
            try (InputStream in = other.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException(label + ": cannot read " + name + ": " + e.getMessage(), e);
        }
    }
}
