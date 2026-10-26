package com.infinitestack.reports.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.reports.dto.DraftRequest;
import com.infinitestack.reports.dto.ReportRequest;
import com.infinitestack.reports.dto.VersionView;
import com.infinitestack.reports.report.Report;
import com.infinitestack.reports.report.ReportVersion;
import com.infinitestack.reports.service.ReportService;
import com.infinitestack.reports.template.TemplateScope;
import com.infinitestack.reports.web.CurrentUser;

/** Report definitions and versions. Running them lives in {@link ExecutionController}. */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping
    public List<Report> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Report create(@RequestBody ReportRequest request, CurrentUser user) {
        return service.create(request, user);
    }

    @GetMapping("/{id}")
    public Report get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public Report update(@PathVariable long id, @RequestBody ReportRequest request, CurrentUser user) {
        return service.update(id, request, user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, CurrentUser user) {
        service.delete(id, user);
    }

    @GetMapping("/{id}/versions")
    public List<ReportVersion> versions(@PathVariable long id) {
        return service.versions(id);
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportVersion createDraft(@PathVariable long id, CurrentUser user) {
        return service.createDraft(id, user);
    }

    @GetMapping("/{id}/versions/{version}")
    public VersionView version(@PathVariable long id, @PathVariable int version) {
        return service.versionView(id, version);
    }

    @PutMapping("/{id}/versions/{version}")
    public VersionView updateDraft(@PathVariable long id, @PathVariable int version,
                                   @RequestBody DraftRequest request, CurrentUser user) {
        return service.updateDraft(id, version, request, user);
    }

    @PostMapping("/{id}/versions/{version}/publish")
    public VersionView publish(@PathVariable long id, @PathVariable int version, CurrentUser user) {
        return service.publish(id, version, user);
    }

    /** The {{variables}} the template of this version can use. */
    @GetMapping("/{id}/versions/{version}/variables")
    public List<TemplateScope.Variable> variables(@PathVariable long id, @PathVariable int version) {
        return service.variables(id, version);
    }
}
