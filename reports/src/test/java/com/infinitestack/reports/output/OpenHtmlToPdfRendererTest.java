package com.infinitestack.reports.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.config.ReportsLimits;
import com.infinitestack.reports.report.PageSettings;
import com.infinitestack.reports.template.HtmlSanitizer;
import com.infinitestack.reports.template.TemplateRenderer;

class OpenHtmlToPdfRendererTest {

    private final OpenHtmlToPdfRenderer pdf = new OpenHtmlToPdfRenderer(ReportsLimits.defaults());
    private final TemplateRenderer templates = new TemplateRenderer(new HtmlSanitizer(), ReportsLimits.defaults());

    private static final String TEMPLATE = """
            <div class="header">Fazenda {{inputs.name}}</div>
            <div class="footer">Page <span class="page"></span></div>
            <h1>{{inputs.name}}</h1>
            <table><thead><tr><th>Municipality</th><th>UF</th></tr></thead>
            <tbody><tr><td>Garça</td><td>SP</td></tr></tbody></table>
            <img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==">
            <div style="page-break-before: always">Second page</div>
            """;

    private static final String CSS = """
            .header { position: running(header); }
            .footer { position: running(footer); }
            .page::after { content: counter(page) " / " counter(pages); }
            @page { @top-center { content: element(header); } @bottom-center { content: element(footer); } }
            """;

    @Test
    void rendersPagesHeadersFootersAndAccents() throws Exception {
        String html = templates.render("Report", TEMPLATE, CSS, new PageSettings("A4", "landscape", 15),
                Map.of("inputs", Map.of("name", "São João")));
        byte[] bytes = pdf.render(html);
        assertEquals("%PDF", new String(bytes, 0, 4));
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertEquals(2, document.getNumberOfPages());
            assertTrue(document.getPage(0).getMediaBox().getWidth() > document.getPage(0).getMediaBox().getHeight(),
                    "landscape");
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Garça"), text);
            assertTrue(text.contains("Fazenda São João"), text);
            assertTrue(text.contains("Page 2 / 2"), text);
        }
    }

    @Test
    void neverLoadsExternalResources() throws Exception {
        // Straight to the renderer, skipping the sanitizer: the renderer must hold on its own.
        byte[] bytes = pdf.render("<html><head><link rel=\"stylesheet\" href=\"http://127.0.0.1:9/x.css\"></head>"
                + "<body><img src=\"http://127.0.0.1:9/x.png\"><p>ok</p></body></html>");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertTrue(new PDFTextStripper().getText(document).contains("ok"));
        }
    }

    @Test
    void enforcesTheSizeLimit() {
        OpenHtmlToPdfRenderer tiny = new OpenHtmlToPdfRenderer(new ReportsLimits(50, 30, 120, 262144, 5242880, 100, 60));
        PdfRenderer.PdfException e = assertThrows(PdfRenderer.PdfException.class, () -> tiny.render("<p>x</p>"));
        assertTrue(e.getMessage().contains("limit"), e.getMessage());
    }
}
