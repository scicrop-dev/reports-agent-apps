package com.infinitestack.reports.report;

import java.util.Set;

import com.infinitestack.reports.engine.DataType;

/**
 * A value the person running the report provides, e.g. propertyId (MASTER section 9).
 *
 * <p>{@code picker} (optional) makes the run form offer a list instead of a text box:
 * {@code gis-dataset} lists the GIS datasets of the Data Library and {@code gis-feature} the
 * features of the dataset picked in the {@code gis-dataset} input of the same form.
 */
public record InputDefinition(String name, DataType type, boolean required, String label, String picker) {

    public static final String PICKER_GIS_DATASET = "gis-dataset";
    public static final String PICKER_GIS_FEATURE = "gis-feature";
    public static final Set<String> PICKERS = Set.of(PICKER_GIS_DATASET, PICKER_GIS_FEATURE);

    public InputDefinition(String name, DataType type, boolean required, String label) {
        this(name, type, required, label, null);
    }
}
