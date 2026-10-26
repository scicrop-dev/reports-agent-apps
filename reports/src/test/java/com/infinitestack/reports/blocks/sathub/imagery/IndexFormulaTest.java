package com.infinitestack.reports.blocks.sathub.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Parses the formulas Satellite Hub stores and checks values, precedence, NaN rules and errors. */
class IndexFormulaTest {

    static final Set<String> BANDS = Set.of("coastal", "blue", "green", "red", "rededge1", "rededge2",
            "rededge3", "nir", "nir08", "nir09", "swir16", "swir22", "scl", "aot", "wvp");

    /** One vegetated pixel, reflectance. */
    static final Map<String, Double> PIXEL = Map.of("blue", 0.04, "green", 0.08, "red", 0.05,
            "rededge1", 0.12, "nir", 0.40, "swir16", 0.20, "swir22", 0.10);

    static double eval(String formula) {
        return IndexFormula.parse(formula, BANDS).evaluate(PIXEL::get);
    }

    @Test
    void satelliteHubFormulas() {
        double e = 1e-12;
        assertEquals((0.40 - 0.05) / (0.40 + 0.05), eval("(nir - red) / (nir + red)"), e);
        assertEquals((0.40 - 0.12) / (0.40 + 0.12), eval("(nir - rededge1) / (nir + rededge1)"), e);
        assertEquals((0.40 - 0.08) / (0.40 + 0.08), eval("(nir - green) / (nir + green)"), e);
        assertEquals((0.40 - 0.20) / (0.40 + 0.20), eval("(nir - swir16) / (nir + swir16)"), e);
        assertEquals(2.5 * (0.40 - 0.05) / (0.40 + 6 * 0.05 - 7.5 * 0.04 + 1),
                eval("2.5 * (nir - red) / (nir + 6 * red - 7.5 * blue + 1)"), e);
        assertEquals(1.5 * (0.40 - 0.05) / (0.40 + 0.05 + 0.5), eval("1.5 * (nir - red) / (nir + red + 0.5)"), e);
        assertEquals((0.08 - 0.40) / (0.08 + 0.40), eval("(green - nir) / (green + nir)"), e);
        assertEquals((0.40 - 0.10) / (0.40 + 0.10), eval("(nir - swir22) / (nir + swir22)"), e);
        // EVI on these values, by hand: 2.5 * 0.35 / 1.4 = 0.625
        assertEquals(0.625, eval("2.5 * (nir - red) / (nir + 6 * red - 7.5 * blue + 1)"), 1e-12);
    }

    @Test
    void precedenceAndAssociativity() {
        assertEquals(7, eval("1 + 2 * 3 + nir * 0"), 1e-12);
        assertEquals(9, eval("(1 + 2) * 3 + nir * 0"), 1e-12);
        assertEquals(1, eval("8 - 4 - 3 + nir * 0"), 1e-12);
        assertEquals(1, eval("8 / 4 / 2 + nir * 0"), 1e-12);
        assertEquals(-0.40, eval("-nir"), 1e-12);
        assertEquals(0.40, eval("--nir"), 1e-12);
        assertEquals(-0.35, eval("-(nir - red)"), 1e-12);
        assertEquals(-4, eval("-2 ** 2 + nir * 0"), 1e-12);
        assertEquals(512, eval("2 ** 3 ** 2 + nir * 0"), 1e-9);
        assertEquals(0.5, eval("2 ** -1 + nir * 0"), 1e-12);
        assertEquals(1, eval("-3 % 2 + nir * 0"), 1e-12);
    }

    @Test
    void numbers() {
        assertEquals(0.5 + 0.001 + 2000 + 1, eval(".5 + 1e-3 + 2E3 + 1. + nir * 0"), 1e-9);
    }

    @Test
    void functions() {
        assertEquals(Math.sqrt(0.40), eval("sqrt(nir)"), 1e-12);
        assertEquals(0.05, eval("min(nir, red)"), 1e-12);
        assertEquals(0.40, eval("max ( nir , red )"), 1e-12);
        assertEquals(0.3, eval("clip(nir, 0, 0.3)"), 1e-12);
        assertEquals(0.40, eval("where(nir - red, nir, red)"), 1e-12);
        assertEquals(0.16, eval("power(nir, 2)"), 1e-12);
        assertEquals(0.35, eval("abs(red - nir)"), 1e-12);
    }

    @Test
    void divisionByZeroAndNaN() {
        IndexFormula ndvi = IndexFormula.parse("(nir - red) / (nir + red)", BANDS);
        assertTrue(Double.isNaN(ndvi.evaluate(b -> 0.0)));
        assertTrue(Double.isNaN(ndvi.evaluate(b -> b.equals("nir") ? Double.NaN : 0.1)));
        assertTrue(Double.isNaN(eval("nir / 0")));
        assertTrue(Double.isNaN(eval("nir % 0")));
    }

    @Test
    void bandsInOrderAndFastPath() {
        IndexFormula evi = IndexFormula.parse("2.5 * (nir - red) / (nir + 6 * red - 7.5 * blue + 1)", BANDS);
        assertEquals(List.of("nir", "red", "blue"), List.copyOf(evi.bands()));
        assertEquals(List.of("nir", "red", "blue"), evi.bandOrder());
        assertEquals(0.625, evi.evaluate(new double[] {0.40, 0.05, 0.04}), 1e-12);
        assertEquals(evi.evaluate(PIXEL::get), evi.evaluate(new double[] {0.40, 0.05, 0.04}), 0);
        assertThrows(IllegalArgumentException.class, () -> evi.evaluate(new double[] {0.4}));
    }

    @Test
    void syntaxErrorsCarryThePosition() {
        assertError("(nir - red / (nir + red)", "Expected ')' but the formula ended");
        assertError("nir - red)", "Unexpected ')' at position 10");
        assertError("nir + * red", "Unexpected '*' at position 7");
        assertError("nir ^ red", "Unexpected '^' at position 5");
        assertError("nir.red", "Unexpected '.'");
        assertError("2nir", "number cannot be followed by a name");
        assertError("1e+ * nir", "Malformed exponent");
        assertError("", "Empty formula");
        assertError("1 + 2", "reads no band");
        assertError("sqrt(nir, red)", "takes 1 argument(s), got 2");
        assertError("eval(nir)", "Unknown function 'eval'");
        assertError("sqrt + nir", "needs arguments");
    }

    @Test
    void unknownBandListsTheKnownOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IndexFormula.parse("(nir - B04) / (nir + B04)", BANDS));
        assertTrue(e.getMessage().contains("Unknown band 'B04'"), e.getMessage());
        assertTrue(e.getMessage().contains("blue, coastal, green"), e.getMessage());
        // Case-sensitive, like the STAC asset names.
        assertThrows(IllegalArgumentException.class, () -> IndexFormula.parse("NIR - red", BANDS));
    }

    static void assertError(String formula, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IndexFormula.parse(formula, BANDS));
        assertTrue(e.getMessage().contains(expected), () -> "'" + e.getMessage() + "' lacks '" + expected + "'");
    }
}
