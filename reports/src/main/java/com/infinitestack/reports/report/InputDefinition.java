package com.infinitestack.reports.report;

import com.infinitestack.reports.engine.DataType;

/** A value the person running the report provides, e.g. propertyId (MASTER section 9). */
public record InputDefinition(String name, DataType type, boolean required, String label) {
}
