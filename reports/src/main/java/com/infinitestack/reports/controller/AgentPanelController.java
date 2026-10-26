package com.infinitestack.reports.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}")
public class AgentPanelController {

    @Value("${infinitestack.plugin.base-path:/api/plugins/reports}")
    private String pluginBasePath;

    @Value("${infinitestack.plugin.assets-path:${infinitestack.plugin.base-path}/assets}")
    private String pluginAssetsPath;

    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("pluginBasePath", pluginBasePath);
        model.addAttribute("pluginAssetsPath", pluginAssetsPath);
        return "isp-index";
    }

    @GetMapping("/status")
    public String status(Model model, HttpServletRequest request) {
        model.addAttribute("pluginBasePath", pluginBasePath);
        model.addAttribute("pluginAssetsPath", pluginAssetsPath);
        model.addAttribute("pluginStatus", "healthy");
        model.addAttribute("pluginPath", request.getRequestURI());
        return "isp-status";
    }
}
