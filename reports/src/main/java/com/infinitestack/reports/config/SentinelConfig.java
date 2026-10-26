package com.infinitestack.reports.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.infinitestack.reports.blocks.sathub.imagery.CogReader;
import com.infinitestack.reports.blocks.sathub.imagery.EarthSearchClient;
import com.infinitestack.reports.blocks.sathub.imagery.HttpCogReader;
import com.infinitestack.reports.blocks.sathub.imagery.HttpRangeFetcher;
import com.infinitestack.reports.blocks.sathub.imagery.SceneSearch;

/**
 * Where the latest-image Block finds Sentinel-2: the Earth Search STAC API (the catalog Satellite Hub
 * uses) and the COGs it points to, read with HTTP Range requests. Nothing here touches the network
 * at startup: the clients only connect when the Block runs.
 */
@Configuration
public class SentinelConfig {

    @Bean
    public SceneSearch sentinelSceneSearch(
            @Value("${reports.sentinel.stac-url:https://earth-search.aws.element84.com/v1}") String stacUrl) {
        return new EarthSearchClient(stacUrl);
    }

    @Bean
    public CogReader sentinelCogReader() {
        return new HttpCogReader(new HttpRangeFetcher(Duration.ofSeconds(30)));
    }
}
