package com.infinitestack.reports.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.reports.readymade.ReadyMadeService;
import com.infinitestack.reports.readymade.ReadyMadeView;
import com.infinitestack.reports.report.Report;
import com.infinitestack.reports.web.CurrentUser;

/** Ready-made reports shipped with the app, and copying one into a new report. */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api/ready-made")
public class ReadyMadeController {

    private final ReadyMadeService service;

    public ReadyMadeController(ReadyMadeService service) {
        this.service = service;
    }

    @GetMapping
    public List<ReadyMadeView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ReadyMadeView.Detail get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/instantiate")
    @ResponseStatus(HttpStatus.CREATED)
    public Report instantiate(@PathVariable String id, @RequestBody(required = false) ReadyMadeView.InstantiateRequest request,
                              CurrentUser user) {
        return service.instantiate(id, request, user);
    }
}
