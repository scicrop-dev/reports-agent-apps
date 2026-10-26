package com.infinitestack.reports.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.reports.repository.SchemaInitializer;
import com.infinitestack.reports.web.CurrentUser;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api")
public class MeController {

    private final SchemaInitializer schemaInitializer;

    public MeController(SchemaInitializer schemaInitializer) {
        this.schemaInitializer = schemaInitializer;
    }

    /** The caller as the host identified them, plus whether the app's tables are ready. */
    @GetMapping("/me")
    public Map<String, Object> me(CurrentUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("username", user.username());
        out.put("role", user.role());
        out.put("principalType", user.principalType());
        out.put("admin", user.isAdmin());
        out.put("databaseReady", schemaInitializer.ensureReady());
        out.put("databaseError", schemaInitializer.lastError());
        return out;
    }
}
