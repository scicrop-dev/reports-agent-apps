package com.infinitestack.reports.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.infinitestack.reports.blocks.skillslab.HostWorkflowClient;
import com.infinitestack.reports.blocks.skillslab.WorkflowRuns;

/**
 * Where the Skills Lab Block runs workflows: the host's internal workflow port. Nothing connects at
 * startup; without the internal token (outside the IS) the Block fails when it runs.
 */
@Configuration
public class SkillsLabConfig {

    @Bean
    public WorkflowRuns hostWorkflowRuns(
            @Value("${infinitestack.host.base-url:http://127.0.0.1:8081}") String hostBaseUrl,
            @Value("${infinitestack.plugin.runtime-id:reports}") String pluginId,
            @Value("${infinitestack.plugin.internal-token:}") String internalToken) {
        return new HostWorkflowClient(RestClient.builder().baseUrl(hostBaseUrl).build(), pluginId, internalToken);
    }
}
