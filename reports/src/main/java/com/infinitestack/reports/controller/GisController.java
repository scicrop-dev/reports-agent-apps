package com.infinitestack.reports.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.reports.blocks.gis.GisAreas;
import com.infinitestack.reports.web.ApiException;
import com.infinitestack.reports.web.CurrentUser;

/**
 * The GIS datasets of the Data Library, for the run form's {@code gis-dataset} and {@code gis-feature}
 * pickers. Read only, through the Block pool.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/reports}/api/gis")
public class GisController {

    private final GisAreas gis;

    public GisController(GisAreas gis) {
        this.gis = gis;
    }

    @GetMapping("/datasets")
    public Map<String, Object> datasets(CurrentUser user) {
        return gis.datasets();
    }

    @GetMapping("/datasets/{dataset}/features")
    public Map<String, Object> features(@PathVariable String dataset, CurrentUser user) {
        try {
            return gis.features(dataset);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }
}
