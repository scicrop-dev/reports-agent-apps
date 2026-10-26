package com.infinitestack.reports.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.infinitestack.reports.dto.PreviewResult;
import com.infinitestack.reports.dto.RerunResult;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.flow.BlockRun;
import com.infinitestack.reports.engine.flow.ExecutionContext;
import com.infinitestack.reports.engine.flow.FlowNode;
import com.infinitestack.reports.engine.flow.FlowExecutor;
import com.infinitestack.reports.engine.flow.FlowRun;
import com.infinitestack.reports.engine.flow.FlowValidationException;
import com.infinitestack.reports.engine.flow.FlowValidator;
import com.infinitestack.reports.engine.flow.ValidatedFlow;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.execution.Execution;
import com.infinitestack.reports.execution.ExecutionError;
import com.infinitestack.reports.execution.ExecutionStatus;
import com.infinitestack.reports.output.PdfRenderer;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.report.Report;
import com.infinitestack.reports.report.ReportVersion;
import com.infinitestack.reports.repository.ExecutionRepository;
import com.infinitestack.reports.template.TemplateException;
import com.infinitestack.reports.template.TemplateRenderer;
import com.infinitestack.reports.template.TemplateScope;
import com.infinitestack.reports.web.ApiException;
import com.infinitestack.reports.web.CurrentUser;

/**
 * Runs report versions (ARCHITECTURE section 6): resolve the version, validate, check the Blocks
 * against the catalog, run the Flow, render the template.
 *
 * <p>A request that is wrong in itself (bad inputs, invalid Flow or template) is refused with 422
 * and leaves no trace. Once those checks pass, a stored execution is created and every later
 * failure — a Block that lost its data, a Block error, a render error — is recorded in it.
 * Previews go through the same path and store nothing.
 */
@Service
public class ExecutionService {

    private static final int HISTORY_LIMIT = 50;

    private final ReportService reports;
    private final FlowValidator flowValidator;
    private final FlowExecutor flowExecutor;
    private final TemplateRenderer templateRenderer;
    private final BlockService blockService;
    private final BlockRegistry registry;
    private final ExecutionRepository repository;
    private final PdfRenderer pdfRenderer;

    public ExecutionService(ReportService reports, FlowValidator flowValidator, FlowExecutor flowExecutor,
                            TemplateRenderer templateRenderer, BlockService blockService, BlockRegistry registry,
                            ExecutionRepository repository, PdfRenderer pdfRenderer) {
        this.reports = reports;
        this.flowValidator = flowValidator;
        this.flowExecutor = flowExecutor;
        this.templateRenderer = templateRenderer;
        this.blockService = blockService;
        this.registry = registry;
        this.repository = repository;
        this.pdfRenderer = pdfRenderer;
    }

    public Execution execute(long reportId, Integer versionNumber, Map<String, Object> rawInputs, CurrentUser user) {
        return execute(reportId, versionNumber, rawInputs, user, null);
    }

    /**
     * Runs a stored execution again: same report version, same inputs (ARCHITECTURE section 36).
     * Only the current implementation of each Block exists in the app, so when a Block changed
     * version since then the answer says so; {@code identicalValues} tells whether every Block
     * produced exactly what it produced the first time.
     */
    public RerunResult rerun(long executionId, CurrentUser user) {
        Execution original = get(executionId);
        List<String> warnings = new ArrayList<>();
        Map<String, String> before = original.blockVersions() == null ? Map.of() : original.blockVersions();
        before.forEach((blockId, version) -> {
            String now = registry.find(blockId).map(b -> b.definition().version()).orElse(null);
            if (now == null) {
                warnings.add("block " + blockId + " " + version + " no longer exists");
            } else if (!now.equals(version)) {
                warnings.add("block " + blockId + " ran as " + version + " and now runs as " + now);
            }
        });
        Execution rerun = execute(original.reportId(), original.reportVersion(), original.inputs(), user, original.id());
        boolean identical = original.status() == ExecutionStatus.COMPLETED && rerun.status() == ExecutionStatus.COMPLETED
                && java.util.Objects.equals(original.values(), rerun.values());
        return new RerunResult(rerun, identical, warnings);
    }

    private Execution execute(long reportId, Integer versionNumber, Map<String, Object> rawInputs, CurrentUser user, Long rerunOf) {
        Prepared prepared = prepare(reportId, versionNumber, rawInputs);
        long executionId = repository.start(reportId, prepared.version().version(), prepared.inputs(), user.id(), rerunOf);
        Outcome outcome = run(prepared, executionId, user);
        repository.finish(executionId, outcome.status(), outcome.values(), outcome.blockVersions(), outcome.error(),
                outcome.finishedAt(), outcome.durationMs(), outcome.blocks());
        return get(executionId);
    }

    public PreviewResult preview(long reportId, Integer versionNumber, Map<String, Object> rawInputs, CurrentUser user) {
        Prepared prepared = prepare(reportId, versionNumber, rawInputs);
        Outcome outcome = run(prepared, null, user);
        Execution execution = new Execution(null, reportId, prepared.version().version(), outcome.status(),
                prepared.inputs(), outcome.values(), outcome.blockVersions(), outcome.error(), user.id(),
                outcome.startedAt(), outcome.finishedAt(), outcome.durationMs(), null, outcome.blocks());
        return new PreviewResult(execution, outcome.html());
    }

    public Execution get(long executionId) {
        return repository.find(executionId)
                .orElseThrow(() -> ApiException.notFound("Execution " + executionId + " not found"));
    }

    public List<Execution> history(long reportId) {
        reports.get(reportId);
        return repository.findByReport(reportId, HISTORY_LIMIT);
    }

    /**
     * Renders the result of a stored execution again from its recorded context and the template of
     * the version it ran — without running any Block (ARCHITECTURE section 36).
     */
    public String resultHtml(long executionId) {
        Execution execution = get(executionId);
        if (execution.status() != ExecutionStatus.COMPLETED) {
            throw ApiException.conflict("Execution " + executionId + " did not complete (" + execution.status() + ")");
        }
        Report report = reports.get(execution.reportId());
        ReportVersion version = reports.version(execution.reportId(), execution.reportVersion());
        try {
            return templateRenderer.render(report.name(), version.templateHtml(), version.templateCss(), version.page(),
                    ExecutionContext.templateModel(execution.inputs(), execution.values(), ExecutionContext.reportMetadata(
                            report.name(), execution.reportVersion(), execution.id(),
                            execution.finishedAt() != null ? execution.finishedAt() : execution.createdAt())));
        } catch (TemplateException e) {
            throw ApiException.conflict("The result cannot be rendered", e.problems());
        }
    }

    /** PDF of a stored execution, generated on demand from its context (ROADMAP D7: not stored). */
    public byte[] resultPdf(long executionId) {
        return toPdf(resultHtml(executionId));
    }

    /** PDF of a preview: runs like {@link #preview} and stores nothing. */
    public byte[] previewPdf(long reportId, Integer versionNumber, Map<String, Object> rawInputs, CurrentUser user) {
        PreviewResult preview = preview(reportId, versionNumber, rawInputs, user);
        if (preview.html() == null) {
            ExecutionError error = preview.execution().error();
            throw ApiException.conflict(error == null ? "The preview failed" : error.message(),
                    error == null ? List.of() : error.details());
        }
        return toPdf(preview.html());
    }

    private byte[] toPdf(String html) {
        try {
            return pdfRenderer.render(html);
        } catch (PdfRenderer.PdfException e) {
            throw ApiException.conflict("The PDF could not be generated", List.of(e.getMessage()));
        }
    }

    private Prepared prepare(long reportId, Integer versionNumber, Map<String, Object> rawInputs) {
        Report report = reports.get(reportId);
        int number;
        if (versionNumber != null) {
            number = versionNumber;
        } else if (report.publishedVersion() != null) {
            number = report.publishedVersion();
        } else {
            throw ApiException.conflict("Report " + reportId + " has no published version; pass the version to run");
        }
        ReportVersion version = reports.version(reportId, number);

        List<String> inputProblems = new ArrayList<>(ReportService.validateInputs(version.inputs()));
        Map<String, Object> inputs = inputProblems.isEmpty() ? coerceInputs(version.inputs(), rawInputs, inputProblems) : Map.of();
        if (!inputProblems.isEmpty()) throw ApiException.invalid("Invalid inputs", inputProblems);

        ValidatedFlow flow;
        try {
            flow = flowValidator.validate(version.flow(), version.inputs());
        } catch (FlowValidationException e) {
            throw ApiException.invalid("The flow of version " + number + " is invalid", e.problems());
        }
        List<String> templateProblems = templateRenderer.validate(version.templateHtml(), version.templateCss(),
                TemplateScope.of(version.inputs(), version.flow(), registry));
        if (!templateProblems.isEmpty()) {
            throw ApiException.invalid("The template of version " + number + " is invalid", templateProblems);
        }
        return new Prepared(report, version, flow, inputs);
    }

    private Outcome run(Prepared prepared, Long executionId, CurrentUser user) {
        Instant startedAt = Instant.now();
        Map<String, String> blockVersions = new LinkedHashMap<>();
        prepared.flow().steps().forEach(s -> blockVersions.put(s.block().definition().id(), s.block().definition().version()));

        // VALIDATING: the Blocks must still be released and have their data.
        List<String> blockProblems = blockService.problemsFor(prepared.version().flow().nodes().stream().map(FlowNode::block).toList());
        if (!blockProblems.isEmpty()) {
            return Outcome.failed(startedAt, Map.of(), blockVersions, List.of(), new ExecutionError(
                    ExecutionStatus.VALIDATING, null, null, "Some blocks cannot be used", blockProblems));
        }

        // RUNNING
        ExecutionMetadata metadata = new ExecutionMetadata(executionId, prepared.report().id(),
                prepared.version().version(), user.id(), user.role());
        FlowRun run = flowExecutor.run(prepared.flow(), prepared.inputs(), metadata);
        Map<String, Map<String, Object>> values = run.context().values();
        if (!run.completed()) {
            FlowRun.Failure failure = run.failure();
            return Outcome.failed(startedAt, values, blockVersions, run.blocks(), new ExecutionError(ExecutionStatus.RUNNING,
                    failure.nodeId(), failure.blockId(), "Block " + failure.blockId() + " failed at node " + failure.nodeId(),
                    List.of(failure.message())));
        }

        // RENDERING
        try {
            String html = templateRenderer.render(prepared.report().name(), prepared.version().templateHtml(),
                    prepared.version().templateCss(), prepared.version().page(), run.context().templateModel(
                            ExecutionContext.reportMetadata(prepared.report().name(), prepared.version().version(),
                                    executionId, Instant.now())));
            Instant finishedAt = Instant.now();
            return new Outcome(ExecutionStatus.COMPLETED, startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis(),
                    values, blockVersions, run.blocks(), null, html);
        } catch (TemplateException e) {
            return Outcome.failed(startedAt, values, blockVersions, run.blocks(), new ExecutionError(ExecutionStatus.RENDERING,
                    null, null, "The template could not be rendered", e.problems()));
        }
    }

    private static Map<String, Object> coerceInputs(List<InputDefinition> definitions, Map<String, Object> raw, List<String> problems) {
        Map<String, Object> given = raw == null ? Map.of() : raw;
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (InputDefinition definition : definitions) {
            Object value = given.get(definition.name());
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (definition.required()) problems.add("input '" + definition.name() + "' is required");
                // An optional input left empty is still in the model, as null: a template may test it
                // ({{#inputs.x}}...) and strict rendering refuses a name that is not there at all.
                else inputs.put(definition.name(), null);
                continue;
            }
            try {
                inputs.put(definition.name(), definition.type().coerce(value));
            } catch (IllegalArgumentException e) {
                problems.add("input '" + definition.name() + "': " + e.getMessage());
            }
        }
        for (String name : given.keySet()) {
            if (definitions.stream().noneMatch(d -> d.name().equals(name))) {
                problems.add("the report has no input '" + name + "'");
            }
        }
        return inputs;
    }

    private record Prepared(Report report, ReportVersion version, ValidatedFlow flow, Map<String, Object> inputs) {}

    private record Outcome(ExecutionStatus status, Instant startedAt, Instant finishedAt, long durationMs,
                           Map<String, Map<String, Object>> values, Map<String, String> blockVersions,
                           List<BlockRun> blocks, ExecutionError error, String html) {

        static Outcome failed(Instant startedAt, Map<String, Map<String, Object>> values, Map<String, String> blockVersions,
                              List<BlockRun> blocks, ExecutionError error) {
            Instant finishedAt = Instant.now();
            return new Outcome(ExecutionStatus.FAILED, startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis(),
                    values, blockVersions, blocks, error, null);
        }
    }
}
