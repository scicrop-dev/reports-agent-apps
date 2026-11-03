package com.infinitestack.reports.blocks.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class GisAreasTest {

    @Test
    void featureLabelPicksTheFirstNamedAttribute() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals("A", GisAreas.featureLabel(json.readTree("{\"id\": 7, \"Nome\": \"A\"}"), 1));
        assertEquals("7", GisAreas.featureLabel(json.readTree("{\"id\": 7, \"x\": \"\"}"), 1));
        assertEquals("Feature 3", GisAreas.featureLabel(json.readTree("{\"nome\": \" \"}"), 3));
        assertEquals("Feature 4", GisAreas.featureLabel(null, 4));
    }
}
