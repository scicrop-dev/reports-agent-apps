package com.infinitestack.reports.readymade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.engine.block.BlockRegistry;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.flow.ExecutionContext;
import com.infinitestack.reports.engine.flow.FlowExecutor;
import com.infinitestack.reports.engine.flow.FlowRun;
import com.infinitestack.reports.engine.flow.FlowValidator;
import com.infinitestack.reports.engine.flow.ValidatedFlow;
import com.infinitestack.reports.output.OpenHtmlToPdfRenderer;
import com.infinitestack.reports.report.InputDefinition;
import com.infinitestack.reports.service.ReportService;
import com.infinitestack.reports.support.CarBlockContractStubs;
import com.infinitestack.reports.support.GeoBlockContractStubs;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.template.HtmlSanitizer;
import com.infinitestack.reports.template.TemplateRenderer;
import com.infinitestack.reports.template.TemplateScope;

/**
 * Every shipped ready-made report must be valid and render, to HTML and to PDF, against the
 * Block contracts it uses. The Blocks here are {@link CarBlockContractStubs}: test doubles of the
 * contracts in docs/BLOCKS.md, run with canned outputs (a full property and a bare one, so both
 * branches of each section render). The rendered files are written to target/ready-made/ to look at.
 */
class ReadyMadeReportsValidityTest {

    private final ReportsLimits limits = ReportsLimits.defaults();
    private final TemplateRenderer templates = new TemplateRenderer(new HtmlSanitizer(), limits);
    private final OpenHtmlToPdfRenderer pdf = new OpenHtmlToPdfRenderer(limits);
    private final FlowExecutor executor = new FlowExecutor(limits);
    private final ReadyMadeCatalog catalog = new ReadyMadeCatalog();

    @Test
    void everyReadyMadeIsValidAndRendersAgainstTheBlockContracts() throws Exception {
        for (boolean bare : List.of(false, true)) {
            List<ReportBlock> blocks = new ArrayList<>(CarBlockContractStubs.all(bare));
            blocks.addAll(GeoBlockContractStubs.all(bare));
            BlockRegistry registry = new BlockRegistry(blocks);
            for (ReadyMadeReport report : catalog.all()) {
                String label = report.id() + (bare ? " (bare)" : " (full)");
                assertEquals(List.of(), ReportService.validateInputs(report.inputs()), label + " inputs");
                ValidatedFlow flow = new FlowValidator(registry, limits).validate(report.flow(), report.inputs());
                TemplateScope scope = TemplateScope.of(report.inputs(), report.flow(), registry);
                assertEquals(List.of(), templates.validate(report.templateHtml(), report.templateCss(), scope), label + " template");
                assertEquals(List.of(), report.page().problems(), label + " page");

                FlowRun run = executor.run(flow, sampleInputs(report.inputs(), bare), new ExecutionMetadata(42L, 1, 1, "u", "USER"));
                assertTrue(run.completed(), label + ": " + run.failure());
                // Execution 42, as in the history; the bare case renders as a preview (no execution id).
                Map<String, Object> model = run.context().templateModel(
                        ExecutionContext.reportMetadata(report.name(), 1, bare ? null : 42L, Instant.parse("2026-10-26T13:00:00Z")));
                String html = templates.render(report.name(), report.templateHtml(), report.templateCss(), report.page(), model);
                byte[] bytes = pdf.render(html);

                Path out = Path.of("target", "ready-made");
                Files.createDirectories(out);
                String file = report.id() + (bare ? "-bare" : "-full");
                Files.writeString(out.resolve(file + ".html"), html);
                Files.write(out.resolve(file + ".pdf"), bytes);

                try (PDDocument document = Loader.loadPDF(bytes)) {
                    String text = new PDFTextStripper().getText(document);
                    assertTrue(document.getNumberOfPages() >= 1, label);
                    assertTrue(text.contains("Página 1 de " + document.getNumberOfPages()), label + ": " + text);
                    if (report.id().equals("car-socioenvironmental")) checkLaudo(html, text, bare, label);
                    if (report.id().equals("car-socioenvironmental-complete")) checkCompleteLaudo(text, bare, label);
                }
            }
        }
    }

    /** The parts of the laudo that depend on data: each branch shows up when it should. */
    private static void checkLaudo(String html, String text, boolean bare, String label) {
        assertTrue(text.contains(CarBlockContractStubs.COD_IMOVEL), label);
        assertTrue(text.contains("Querência / MT"), label);
        assertTrue(html.contains("src=\"data:image/png;base64,"), label + ": the map image must survive the sanitizer");
        assertTrue(text.contains("26/10/2026 10:00"), label);
        if (bare) {
            assertTrue(text.contains("Prévia, não registrada"), label);
            assertTrue(text.contains("Não há Reserva Legal declarada"), label);
            assertTrue(text.contains("Nenhuma sobreposição"), label);
            assertTrue(text.contains("Nenhuma feição de uso"), label);
            assertFalse(text.contains("removido da base"), label);
        } else {
            assertTrue(text.contains("nº 42"), label);
            assertTrue(text.contains("20.09%"), label);
            assertTrue(text.contains("Reserva Legal total (2 feição(ões), 251.2 ha)"), label);
            assertTrue(text.contains("MT-5107065-0F1E2D3C4B5A69788796A5B4C3D2E1F0"), label);
            assertTrue(text.contains("2 imóvel(is)"), label);
            assertTrue(text.contains("removido da base do SICAR em 2024-05-10"), label);
            assertTrue(text.contains("vigente"), label);
            assertFalse(text.contains("Não há Reserva Legal declarada"), label);
            assertFalse(text.contains("Nenhuma sobreposição"), label);
        }
    }

    /** The BR Geo sections of the complete laudo: the alert and the "not monitored" branches. */
    private static void checkCompleteLaudo(String text, boolean bare, String label) {
        if (bare) {
            assertTrue(text.contains("nenhum encontrado"), label);
            assertTrue(text.contains("área não monitorada pelo DETER"), label);
            assertTrue(text.contains("Nenhum termo de embargo"), label);
            assertTrue(text.contains("O IBGE não publicou lavouras"), label);
        } else {
            assertTrue(text.contains("1 termo(s) ativo(s)"), label);
            assertTrue(text.contains("4 alerta(s)"), label);
            assertTrue(text.contains("PRODES Amazonia: 44.41 ha"), label);
            assertTrue(text.contains("Soja (em grão)"), label);
            assertTrue(text.contains("Bioma predominante"), label);
        }
    }

    /** A value per input, by type; optional inputs stay empty in the bare case. */
    private static Map<String, Object> sampleInputs(List<InputDefinition> inputs, boolean bare) {
        Map<String, Object> values = new HashMap<>();
        for (InputDefinition input : inputs) {
            if (bare && !input.required()) {
                values.put(input.name(), null); // as the service sends an optional input left empty
                continue;
            }
            Object value = switch (input.type()) {
                case NUMBER -> 16991;
                case GEOMETRY -> Map.of("type", "Polygon", "coordinates", List.of(List.of(
                        List.of(-52.21, -12.61), List.of(-52.17, -12.61), List.of(-52.17, -12.64), List.of(-52.21, -12.61))));
                default -> "codImovel".equals(input.name()) ? CarBlockContractStubs.COD_IMOVEL : "sample";
            };
            if ("index".equals(input.name())) value = "ndvi";
            values.put(input.name(), value);
        }
        return values;
    }
}
