package com.infinitestack.reports.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api")
public class PluginRuntimeController {

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }
}
