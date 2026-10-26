package com.infinitestack.reports.output;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.config.ReportsLimits;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import jakarta.annotation.PreDestroy;

/**
 * PDF with openhtmltopdf: pure Java, so the .ispz needs nothing installed on the host. It supports
 * CSS 2.1 plus paged media ({@code @page}, page breaks, running headers and footers, page counters);
 * flex and grid are not supported, which templates must keep in mind.
 *
 * <p>The HTML is parsed by jsoup first because openhtmltopdf needs well-formed XHTML and the
 * template does not have to be. No resource is loaded from outside: only data: URIs pass.
 */
@Component
public class OpenHtmlToPdfRenderer implements PdfRenderer {

    private final ReportsLimits limits;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "reports-pdf");
        thread.setDaemon(true);
        return thread;
    });

    public OpenHtmlToPdfRenderer(ReportsLimits limits) {
        this.limits = limits;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    @Override
    public byte[] render(String html) throws PdfException {
        Future<byte[]> future = worker.submit(() -> renderNow(html));
        try {
            byte[] pdf = future.get(limits.pdfTimeoutSeconds(), TimeUnit.SECONDS);
            if (pdf.length > limits.maxPdfBytes()) {
                throw new PdfException("the PDF has " + pdf.length + " bytes; the limit is " + limits.maxPdfBytes(), null);
            }
            return pdf;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PdfException("the PDF took longer than " + limits.pdfTimeoutSeconds() + "s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PdfException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new PdfException("the PDF could not be generated: " + cause.getMessage(), cause);
        }
    }

    private static byte[] renderNow(String html) throws Exception {
        org.w3c.dom.Document document = new W3CDom().fromJsoup(Jsoup.parse(html));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useExternalResourceAccessControl((uri, type) -> uri != null && uri.startsWith("data:"),
                ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
        builder.useExternalResourceAccessControl((uri, type) -> uri != null && uri.startsWith("data:"),
                ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
        builder.withW3cDocument(document, "about:blank");
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }
}
