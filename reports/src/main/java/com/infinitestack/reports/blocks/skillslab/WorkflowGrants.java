package com.infinitestack.reports.blocks.skillslab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the grants file the IS admin writes for this app ({@code /opt/infinitestack/etc/<pluginId>-workflows.json},
 * the back's {@code AppWorkflowAccess}): {"workflows": [{"projectId": "...", "workflow": "..."}]}. The app
 * runs on the same file system, so a Block that needs a workflow can find its project there instead of
 * asking the admin to set it twice.
 */
public final class WorkflowGrants {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowGrants() {}

    /** The project of the first grant of {@code workflow}; empty when the file is missing, unreadable or has none. */
    public static Optional<String> projectOf(Path grantsFile, String workflow) {
        if (grantsFile == null || workflow == null || !Files.isRegularFile(grantsFile)) return Optional.empty();
        try {
            for (JsonNode grant : JSON.readTree(grantsFile.toFile()).path("workflows")) {
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
}
