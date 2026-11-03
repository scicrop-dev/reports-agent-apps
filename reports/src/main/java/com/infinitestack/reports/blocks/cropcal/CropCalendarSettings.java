package com.infinitestack.reports.blocks.cropcal;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.blocks.skillslab.WorkflowGrants;

/**
 * Where is-crop-calendar runs for this app and how it is asked.
 *
 * <p>Project: {@code reports.cropcal.project-id} ({@code REPORTS_CROPCAL_PROJECT_ID}) when set, else the
 * project of the {@code is-crop-calendar} grant in {@code /opt/infinitestack/etc/<pluginId>-workflows.json},
 * the file where the IS admin already names it. Staging: the lake staging shared with is-geo-sam.
 */
@Component
public class CropCalendarSettings {

    private final String pluginId;
    private final String workflow;
    private final String projectId;
    private final Path grantsFile;
    private final Path stagingDir;

    @Autowired
    public CropCalendarSettings(@Value("${infinitestack.plugin.runtime-id:reports}") String pluginId,
                                @Value("${reports.cropcal.workflow:is-crop-calendar}") String workflow,
                                @Value("${reports.cropcal.project-id:}") String projectId,
                                @Value("${reports.cropcal.grants-dir:/opt/infinitestack/etc}") String grantsDir,
                                @Value("${reports.cropcal.staging-dir:/opt/infinitestack/var/lake/staging}") String stagingDir) {
        this(pluginId, workflow, projectId, Path.of(grantsDir).resolve(pluginId + "-workflows.json"), Path.of(stagingDir));
    }

    public CropCalendarSettings(String pluginId, String workflow, String projectId, Path grantsFile, Path stagingDir) {
        this.pluginId = pluginId;
        this.workflow = workflow == null || workflow.isBlank() ? "is-crop-calendar" : workflow.trim();
        this.projectId = projectId == null || projectId.isBlank() ? null : projectId.trim();
        this.grantsFile = grantsFile;
        this.stagingDir = stagingDir;
    }

    /** Settings with the defaults and nothing configured (definitions only, tests). */
    public static CropCalendarSettings defaults() {
        return new CropCalendarSettings("reports", "is-crop-calendar", "", Path.of("/nonexistent/reports-workflows.json"),
                Path.of("/nonexistent"));
    }

    public String workflow() {
        return workflow;
    }

    public Path grantsFile() {
        return grantsFile;
    }

    public Path stagingDir() {
        return stagingDir;
    }

    /** The configured project, else the one of the workflow's grant; empty when neither names one. */
    public Optional<String> projectId() {
        return projectId != null ? Optional.of(projectId) : WorkflowGrants.projectOf(grantsFile, workflow);
    }

    public String missingProjectMessage() {
        return "is-crop-calendar has no Skills Lab project for the " + pluginId + " app: the IS admin grants the workflow in "
                + grantsFile + " ({\"workflows\":[{\"projectId\":\"<project>\",\"workflow\":\"" + workflow + "\"}]}) "
                + "or sets reports.cropcal.project-id (REPORTS_CROPCAL_PROJECT_ID)";
    }

    /** The run params: the file and appId, the owner of the gold tables when the IS does not set IS_APP_ID. */
    public Map<String, Object> params(String fileName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("file", fileName);
        params.put("appId", pluginId);
        return params;
    }
}
