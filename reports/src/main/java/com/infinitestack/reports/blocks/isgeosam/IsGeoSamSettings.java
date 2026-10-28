package com.infinitestack.reports.blocks.isgeosam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where is-geo-sam runs for this app and how it is asked.
 *
 * <p>The project needs no setting of its own: the IS admin already names it when granting the
 * workflow to the app, in {@code /opt/infinitestack/etc/<pluginId>-workflows.json} (the back's
 * {@code AppWorkflowAccess}, read on every call). The app runs on the same file system and reads
 * that file to find the project of the {@code is-geo-sam} grant. {@code reports.isgeosam.project-id}
 * still overrides it, for an instance that grants the workflow in more than one project.
 */
@Component
public class IsGeoSamSettings {

    private final String pluginId;
    private final String workflow;
    private final String projectId;
    private final Path grantsFile;
    private final Path stagingDir;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public IsGeoSamSettings(@Value("${infinitestack.plugin.runtime-id:reports}") String pluginId,
                            @Value("${reports.isgeosam.workflow:is-geo-sam}") String workflow,
                            @Value("${reports.isgeosam.project-id:}") String projectId,
                            @Value("${reports.isgeosam.grants-dir:/opt/infinitestack/etc}") String grantsDir,
                            @Value("${reports.isgeosam.staging-dir:/opt/infinitestack/var/lake/staging}") String stagingDir) {
        this(pluginId, workflow, projectId, Path.of(grantsDir).resolve(pluginId + "-workflows.json"), Path.of(stagingDir));
    }

    IsGeoSamSettings(String pluginId, String workflow, String projectId, Path grantsFile, Path stagingDir) {
        this.pluginId = pluginId;
        this.workflow = workflow;
        this.projectId = projectId == null || projectId.isBlank() ? null : projectId.trim();
        this.grantsFile = grantsFile;
        this.stagingDir = stagingDir;
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

    /**
     * The project to run is-geo-sam in: {@code reports.isgeosam.project-id} when set, else the first
     * grant of the workflow in the grants file. Empty when neither names one.
     */
    public Optional<String> projectId() {
        if (projectId != null) return Optional.of(projectId);
        if (!Files.isRegularFile(grantsFile)) return Optional.empty();
        try {
            for (JsonNode grant : json.readTree(grantsFile.toFile()).path("workflows")) {
                String project = grant.path("projectId").asText("").trim();
                if (workflow.equals(grant.path("workflow").asText("").trim()) && !project.isEmpty()) {
                    return Optional.of(project);
                }
            }
        } catch (Exception e) {
            // unreadable: the back grants nothing either; the caller says what to fix
        }
        return Optional.empty();
    }

    /** What to tell the admin when no project is found. */
    public String missingProjectMessage() {
        return "is-geo-sam is not granted to the " + pluginId + " app: the IS admin writes " + grantsFile
                + " with {\"workflows\":[{\"projectId\":\"<project of is-geo-sam>\",\"workflow\":\"" + workflow + "\"}]}";
    }

    /**
     * The run params plus {@code appId}: the owner of the gold tables in the catalog. The script
     * prefers the authenticated IS_APP_ID the back sets (infinitestack-back#1287) and uses this one
     * only on instances without it.
     */
    public Map<String, Object> params(Map<String, Object> request) {
        Map<String, Object> params = new LinkedHashMap<>(request);
        params.put("appId", pluginId);
        return params;
    }
}
