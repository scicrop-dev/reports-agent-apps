package com.infinitestack.reports.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogColumn(String name, String type, String status, String lastUpdate) {
}
