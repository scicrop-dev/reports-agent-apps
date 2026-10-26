package com.infinitestack.reports.readymade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.infinitestack.reports.report.Report;
import com.infinitestack.reports.service.BlockService;
import com.infinitestack.reports.service.ReportService;
import com.infinitestack.reports.web.ApiException;
import com.infinitestack.reports.web.CurrentUser;

/**
 * The ready-made reports catalog: lists them with the usability of their Blocks and copies one
 * into a new report owned by the caller. The copy is the user's own report from then on; it only
 * remembers which ready-made (id and version) it came from.
 */
@Service
public class ReadyMadeService {

    private final ReadyMadeCatalog catalog;
    private final BlockService blockService;
    private final ReportService reportService;

    public ReadyMadeService(ReadyMadeCatalog catalog, BlockService blockService, ReportService reportService) {
        this.catalog = catalog;
        this.blockService = blockService;
        this.reportService = reportService;
    }

    public List<ReadyMadeView> list() {
        // Several ready-mades share Blocks; check each Block once per request.
        Map<String, String> problems = new HashMap<>();
        return catalog.all().stream().map(r -> view(r, problems)).toList();
    }

    public ReadyMadeView.Detail get(String id) {
        ReadyMadeReport report = find(id);
        return new ReadyMadeView.Detail(view(report, new HashMap<>()), report);
    }

    /** Creates a report with version 1 as a DRAFT holding the ready-made content, even if Blocks are missing. */
    public Report instantiate(String id, ReadyMadeView.InstantiateRequest request, CurrentUser user) {
        ReadyMadeReport report = find(id);
        String name = request == null || request.name() == null || request.name().isBlank() ? report.name() : request.name();
        return reportService.createFromReadyMade(report, name, user);
    }

    private ReadyMadeReport find(String id) {
        return catalog.find(id).orElseThrow(() -> ApiException.notFound("Unknown ready-made report: " + id));
    }

    private ReadyMadeView view(ReadyMadeReport report, Map<String, String> problems) {
        List<ReadyMadeView.RequiredBlock> blocks = report.requiredBlocks().stream().map(blockId -> {
            // "" marks a usable Block in the per-request cache (a null value cannot be cached).
            String problem = problems.computeIfAbsent(blockId,
                    b -> String.join("; ", blockService.problemsFor(List.of(b))));
            return new ReadyMadeView.RequiredBlock(blockId, problem.isEmpty(), problem.isEmpty() ? null : problem);
        }).toList();
        return new ReadyMadeView(report.id(), report.version(), report.name(), report.description(), report.category(),
                report.inputs(), blocks, blocks.stream().allMatch(ReadyMadeView.RequiredBlock::usable));
    }
}
