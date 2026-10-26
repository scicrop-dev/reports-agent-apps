package com.infinitestack.reports.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.reports.dto.ExecuteRequest;
import com.infinitestack.reports.dto.PreviewResult;
import com.infinitestack.reports.execution.Execution;
import com.infinitestack.reports.service.ExecutionService;
import com.infinitestack.reports.web.ApiException;
import com.infinitestack.reports.web.CurrentUser;

/** Running reports and reading results (ARCHITECTURE section 39: execution and result are separate). */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api")
public class ExecutionController {

    /**
     * The report HTML is already sanitized; this header is a second barrier for when the result
     * URL is opened directly: no script, no network, nothing but inline styles and data: images.
     */
    static final String RESULT_CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox";

    private final ExecutionService service;

    public ExecutionController(ExecutionService service) {
        this.service = service;
    }

    @PostMapping("/reports/{id}/execute")
    public Execution execute(@PathVariable long id, @RequestBody(required = false) ExecuteRequest request, CurrentUser user) {
        ExecuteRequest body = request == null ? new ExecuteRequest(null, null) : request;
        return service.execute(id, body.version(), body.inputs(), user);
    }

    /** Runs and renders without storing anything; the answer carries the HTML. */
    @PostMapping("/reports/{id}/preview")
    public PreviewResult preview(@PathVariable long id, @RequestBody(required = false) ExecuteRequest request, CurrentUser user) {
        ExecuteRequest body = request == null ? new ExecuteRequest(null, null) : request;
        return service.preview(id, body.version(), body.inputs(), user);
    }

    @PostMapping("/reports/{id}/preview/pdf")
    public ResponseEntity<byte[]> previewPdf(@PathVariable long id, @RequestBody(required = false) ExecuteRequest request,
                                             CurrentUser user) {
        ExecuteRequest body = request == null ? new ExecuteRequest(null, null) : request;
        return pdf(service.previewPdf(id, body.version(), body.inputs(), user), "report-" + id + "-preview.pdf");
    }

    @GetMapping("/reports/{id}/executions")
    public List<Execution> history(@PathVariable long id) {
        return service.history(id);
    }

    @GetMapping("/executions/{id}")
    public Execution get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/executions/{id}/result")
    public ResponseEntity<?> result(@PathVariable long id, @RequestParam(defaultValue = "html") String format) {
        if ("pdf".equalsIgnoreCase(format)) {
            return pdf(service.resultPdf(id), "execution-" + id + ".pdf");
        }
        if (!"html".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("Unsupported format '" + format + "'; use html or pdf");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Security-Policy", RESULT_CSP)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.resultHtml(id));
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }
}
