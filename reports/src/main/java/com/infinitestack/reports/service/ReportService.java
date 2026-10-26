package com.infinitestack.reports.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.reports.diff.LineDiff;
import com.infinitestack.reports.dto.DraftRequest;
import com.infinitestack.reports.dto.VersionDiff;
import com.infinitestack.reports.dto.ReportRequest;
import com.infinitestack.reports.dto.ValidationReport;
import com.infinitestack.reports.dto.VersionView;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.flow.FlowDefinition;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.engine.flow.FlowValidationException;
import com.infinitestack.reports.engine.flow.FlowValidator;
import com.infinitestack.reports.readymade.ReadyMadeReport;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.report.PageSettings;
import com.infinitestack.reports.report.Report;
import com.infinitestack.reports.report.ReportVersion;
import com.infinitestack.reports.report.VersionStatus;
import com.infinitestack.reports.repository.ReportRepository;
import com.infinitestack.reports.template.TemplateRenderer;
import com.infinitestack.reports.template.TemplateScope;
import com.infinitestack.reports.web.ApiException;
import com.infinitestack.reports.web.CurrentUser;

/**
 * Report definitions and their versions (ARCHITECTURE section 39: definition is separate from
 * execution). Everyone sees every report; only the owner or an admin changes one. A report has at
 * most one draft at a time, and a published version never changes again.
 */
@Service
public class ReportService {

    private static final Pattern INPUT_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*");

    private final ReportRepository repository;
    private final FlowValidator flowValidator;
    private final TemplateRenderer templateRenderer;
    private final BlockRegistry registry;
    private final BlockService blockService;
    private final ObjectMapper mapper;

    public ReportService(ReportRepository repository, FlowValidator flowValidator, TemplateRenderer templateRenderer,
                         BlockRegistry registry, BlockService blockService, ObjectMapper mapper) {
        this.mapper = mapper;
        this.repository = repository;
        this.flowValidator = flowValidator;
        this.templateRenderer = templateRenderer;
        this.registry = registry;
        this.blockService = blockService;
    }

    public List<Report> list() {
        return repository.findAll();
    }

    public Report get(long id) {
        return repository.find(id).orElseThrow(() -> ApiException.notFound("Report " + id + " not found"));
    }

    /** Creates the report with an empty version 1 draft. */
    @Transactional
    public Report create(ReportRequest request, CurrentUser user) {
        String name = requireName(request);
        long id = repository.insert(name, request.description(), user.id(), user.username());
        Instant now = Instant.now();
        repository.insertVersion(new ReportVersion(id, 1, VersionStatus.DRAFT, List.of(), FlowDefinition.empty(),
                "", "", PageSettings.defaults(), user.id(), now, now, null));
        return get(id);
    }

    /**
     * Creates the caller's own copy of a ready-made report: version 1 as a DRAFT holding its content,
     * with the origin (id and version) recorded. The draft is not validated here: Blocks may still be
     * unreleased, and the version view says so.
     */
    @Transactional
    public Report createFromReadyMade(ReadyMadeReport readyMade, String name, String description, CurrentUser user) {
        String checked = requireName(new ReportRequest(name, description));
        long id = repository.insert(checked, description, user.id(), user.username(),
                readyMade.id(), readyMade.version());
        Instant now = Instant.now();
        repository.insertVersion(new ReportVersion(id, 1, VersionStatus.DRAFT, readyMade.inputs(), readyMade.flow(),
                readyMade.templateHtml(), readyMade.templateCss(), readyMade.page(), user.id(), now, now, null));
        return get(id);
    }

    public Report update(long id, ReportRequest request, CurrentUser user) {
        Report report = editable(id, user);
        repository.update(report.id(), requireName(request), request.description());
        return get(id);
    }

    public void delete(long id, CurrentUser user) {
        repository.markDeleted(editable(id, user).id());
    }

    public List<ReportVersion> versions(long id) {
        get(id);
        return repository.findVersions(id);
    }

    public ReportVersion version(long id, int versionNumber) {
        get(id);
        return repository.findVersion(id, versionNumber)
                .orElseThrow(() -> ApiException.notFound("Report " + id + " has no version " + versionNumber));
    }

    public VersionView versionView(long id, int versionNumber) {
        ReportVersion version = version(id, versionNumber);
        return new VersionView(version, validate(version));
    }

    /** A new draft, copied from the latest version. Refused while another draft exists. */
    @Transactional
    public ReportVersion createDraft(long id, CurrentUser user) {
        editable(id, user);
        List<ReportVersion> versions = repository.findVersions(id);
        ReportVersion latest = versions.get(0);
        if (latest.status() == VersionStatus.DRAFT) {
            throw ApiException.conflict("Report " + id + " already has a draft: version " + latest.version());
        }
        Instant now = Instant.now();
        ReportVersion draft = new ReportVersion(id, latest.version() + 1, VersionStatus.DRAFT, latest.inputs(),
                latest.flow(), latest.templateHtml(), latest.templateCss(), latest.page(), user.id(), now, now, null);
        repository.insertVersion(draft);
        return draft;
    }

    /** Saves a draft even when it has problems; the answer says what they are. */
    public VersionView updateDraft(long id, int versionNumber, DraftRequest request, CurrentUser user) {
        editable(id, user);
        ReportVersion current = version(id, versionNumber);
        if (current.status() != VersionStatus.DRAFT) {
            throw ApiException.conflict("Version " + versionNumber + " is published and cannot change; create a new draft");
        }
        List<InputDefinition> inputs = request.inputs() == null ? List.of() : request.inputs();
        FlowDefinition flow = request.flow() == null ? FlowDefinition.empty() : request.flow();
        String html = request.templateHtml() == null ? "" : request.templateHtml();
        String css = request.templateCss() == null ? "" : request.templateCss();
        PageSettings page = PageSettings.orDefaults(request.page());
        if (!repository.updateDraft(id, versionNumber, inputs, flow, html, css, page)) {
            throw ApiException.conflict("Version " + versionNumber + " was published meanwhile");
        }
        return versionView(id, versionNumber);
    }

    /** Freezes a draft. Only a version without any problem can be published. */
    @Transactional
    public VersionView publish(long id, int versionNumber, CurrentUser user) {
        editable(id, user);
        ReportVersion version = version(id, versionNumber);
        if (version.status() != VersionStatus.DRAFT) {
            throw ApiException.conflict("Version " + versionNumber + " is already published");
        }
        ValidationReport validation = validate(version);
        if (!validation.valid()) {
            throw ApiException.invalid("Version " + versionNumber + " has problems and cannot be published", validation.all());
        }
        if (!repository.publish(id, versionNumber)) {
            throw ApiException.conflict("Version " + versionNumber + " was published meanwhile");
        }
        return versionView(id, versionNumber);
    }

    /**
     * Compares two versions part by part. Structured parts (inputs, flow, page) are compared as
     * pretty-printed JSON so a diff line is a meaningful field.
     */
    public VersionDiff diff(long id, int from, int to) {
        ReportVersion a = version(id, from);
        ReportVersion b = version(id, to);
        List<VersionDiff.Part> parts = new ArrayList<>();
        parts.add(part("inputs", pretty(a.inputs()), pretty(b.inputs())));
        parts.add(part("flow", pretty(a.flow()), pretty(b.flow())));
        parts.add(part("page", pretty(a.page()), pretty(b.page())));
        parts.add(part("templateHtml", a.templateHtml(), b.templateHtml()));
        parts.add(part("templateCss", a.templateCss(), b.templateCss()));
        return new VersionDiff(id, from, to, parts);
    }

    private static VersionDiff.Part part(String name, String before, String after) {
        List<LineDiff.Line> lines = LineDiff.diff(before, after);
        return new VersionDiff.Part(name, LineDiff.changed(lines), lines);
    }

    private String pretty(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<TemplateScope.Variable> variables(long id, int versionNumber) {
        ReportVersion version = version(id, versionNumber);
        return TemplateScope.of(version.inputs(), version.flow(), registry).variables();
    }

    public ValidationReport validate(ReportVersion version) {
        List<String> inputProblems = validateInputs(version.inputs());
        List<String> flowProblems = List.of();
        try {
            flowValidator.validate(version.flow(), version.inputs());
        } catch (FlowValidationException e) {
            flowProblems = e.problems();
        }
        TemplateScope scope = TemplateScope.of(version.inputs(), version.flow(), registry);
        List<String> templateProblems = templateRenderer.validate(version.templateHtml(), version.templateCss(), scope);
        List<String> blockProblems = blockService.problemsFor(version.flow().nodes().stream()
                .map(FlowNode::block).filter(b -> b != null && registry.find(b).isPresent()).toList());
        return new ValidationReport(inputProblems, flowProblems, templateProblems,
                PageSettings.orDefaults(version.page()).problems(), blockProblems);
    }

    public static List<String> validateInputs(List<InputDefinition> inputs) {
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (InputDefinition input : inputs) {
            if (input.name() == null || !INPUT_NAME.matcher(input.name()).matches()) {
                problems.add("input name '" + input.name() + "' is invalid (letters, digits and _; starts with a letter)");
            } else if (!names.add(input.name())) {
                problems.add("duplicate input '" + input.name() + "'");
            }
            if (input.type() == null) problems.add("input '" + input.name() + "' has no type");
        }
        return problems;
    }

    private Report editable(long id, CurrentUser user) {
        Report report = get(id);
        if (!user.canEdit(report.ownerId())) {
            throw ApiException.forbidden("Only the owner or an admin can change report " + id);
        }
        return report;
    }

    private static String requireName(ReportRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        if (request.name().length() > 200) throw ApiException.badRequest("name has more than 200 characters");
        return request.name().trim();
    }
}
