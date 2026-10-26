package com.infinitestack.reports.blocks.sathub.imagery;

import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * A spectral index formula as Satellite Hub stores it ({@code gold.satellite_hub_analytics_indices.formula}),
 * e.g. {@code 2.5 * (nir - red) / (nir + 6 * red - 7.5 * blue + 1)}: numbers, band names (STAC asset
 * names), + - * /, unary minus, parentheses. Parsed once, evaluated per pixel. Division by zero and any
 * NaN operand give NaN.
 *
 * <p>OWNER: agent 2 (STAC + formulas). Stub until implemented.
 */
public final class IndexFormula {

    private IndexFormula() {}

    /** @throws IllegalArgumentException with a readable reason for a syntax error or an unknown band */
    public static IndexFormula parse(String formula, Set<String> knownBands) {
        throw new UnsupportedOperationException("IndexFormula not implemented yet");
    }

    /** Band names the formula reads. */
    public Set<String> bands() {
        throw new UnsupportedOperationException("IndexFormula not implemented yet");
    }

    /** Value for one pixel; {@code band} returns the reflectance of a band at that pixel. */
    public double evaluate(ToDoubleFunction<String> band) {
        throw new UnsupportedOperationException("IndexFormula not implemented yet");
    }
}
