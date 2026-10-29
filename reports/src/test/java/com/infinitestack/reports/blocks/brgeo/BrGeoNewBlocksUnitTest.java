package com.infinitestack.reports.blocks.brgeo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.brgeo.BrGeoSoilProfilesBlock.Granulometry;
import com.infinitestack.reports.blocks.brgeo.BrGeoSoilProfilesBlock.Layer;
import com.infinitestack.reports.blocks.brgeo.BrGeoSoilUnitsBlock.Restriction;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.ReportBlock;

/** Pure functions and contracts of the BR Geo soil, census, PAM history and ZARC cultivar Blocks. */
class BrGeoNewBlocksUnitTest {

    @Test
    void contractsAreDocumentedAndListTheirTables() {
        List<ReportBlock> blocks = List.of(new BrGeoSoilUnitsBlock(null), new BrGeoSoilProfilesBlock(null),
                new BrGeoCensusEvidenceBlock(null), new BrGeoCropProductionHistoryBlock(null), new BrGeoZarcCultivarsBlock(null),
                new BrGeoZarcRiskBlock(null));
        for (ReportBlock block : blocks) {
            BlockDefinition d = block.definition();
            assertTrue(d.id().startsWith("brgeo-"), d.id());
            assertFalse(d.dataRequirements().isEmpty(), d.id());
            assertEquals("BR Geo", block.docs().category(), d.id());
            assertFalse(block.docs().howItWorks().isEmpty(), d.id());
            assertFalse(block.docs().limitations().isEmpty(), d.id());
            assertTrue(block.docs().example().contains("<node>"), d.id());
        }
        assertEquals("1.1.0", new BrGeoZarcRiskBlock(null).definition().version());
    }

    // ---------------------------------------------------------------- soil units

    @Test
    void soilRestrictionsComeFromReliefPhasesAndOrder() {
        assertEquals(List.of(Restriction.STEEP_RELIEF, Restriction.STONY),
                BrGeoSoilUnitsBlock.restrictions("ARGISSOLOS", "ondulado e forte ondulado", "pedregosa", null));
        assertEquals(List.of(Restriction.STEEP_RELIEF, Restriction.ROCKY),
                BrGeoSoilUnitsBlock.restrictions("NEOSSOLOS", "Montanhoso", "não pedregosa", "rochosa"));
        assertEquals(List.of(Restriction.ORGANOSOL), BrGeoSoilUnitsBlock.restrictions("ORGANOSSOLOS", "plano", "", "  "));
        assertEquals(List.of(Restriction.VERTISOL), BrGeoSoilUnitsBlock.restrictions("Vertissolos", "suave ondulado", null, null));
        assertEquals(List.of(), BrGeoSoilUnitsBlock.restrictions("LATOSSOLOS", "plano e suave ondulado", null, null));
        assertFalse(BrGeoSoilUnitsBlock.phasePresent("Sem pedregosidade"));
        assertTrue(BrGeoSoilUnitsBlock.phasePresent("moderadamente rochosa"));
    }

    @Test
    void componentPiecesStaySeparate() {
        assertEquals(List.of("LATOSSOLO VERMELHO ... forte ondulad", "NEOSSOLO LITÓLICO"),
                BrGeoSoilUnitsBlock.components("LATOSSOLO VERMELHO ... forte ondulad", "NEOSSOLO LITÓLICO", null, " "));
    }

    // ---------------------------------------------------------------- soil profiles

    @Test
    void granulometryIsWeightedByTheCentimetresOfTheRange() {
        // A 0-20 (clay 200 g/kg), B 20-60 (clay 500): 0-40 = (20*200 + 20*500) / 40 = 350 g/kg = 35 %
        Granulometry g = BrGeoSoilProfilesBlock.weighted(List.of(
                new Layer(0, 20, 600.0, 200.0, 200.0),
                new Layer(20, 60, 300.0, 200.0, 500.0),
                new Layer(60, 120, 100.0, 100.0, 800.0)), 0, 40);
        assertEquals(45.0, g.sandPct());
        assertEquals(20.0, g.siltPct());
        assertEquals(35.0, g.clayPct());
        assertEquals(40, g.coveredCm());

        Granulometry partial = BrGeoSoilProfilesBlock.weighted(List.of(
                new Layer(0, 10, 500.0, 100.0, 400.0),
                new Layer(10, 30, null, null, null),
                new Layer(null, 30, 1.0, 1.0, 1.0)), 0, 40);
        assertEquals(40.0, partial.clayPct());
        assertEquals(10, partial.coveredCm());

        Granulometry none = BrGeoSoilProfilesBlock.weighted(List.of(new Layer(50, 80, 1.0, 1.0, 1.0)), 0, 40);
        assertNull(none.clayPct());
        assertEquals(0, none.coveredCm());
    }

    // ---------------------------------------------------------------- census

    @Test
    void censusStatusFollowsTheMethodology() {
        assertEquals("dados_indisponiveis", BrGeoCensusEvidenceBlock.status(false, 0, 0, 0, null, 30));
        assertEquals("trajeto_nao_encontrado", BrGeoCensusEvidenceBlock.status(true, 0, 0, 0, null, 30));
        assertEquals("trajeto_interno", BrGeoCensusEvidenceBlock.status(true, 2, 1, 120.5, 0.0, 30));
        assertEquals("trajeto_de_borda", BrGeoCensusEvidenceBlock.status(true, 1, 1, 0, 0.0, 30));
        assertEquals("trajeto_de_borda", BrGeoCensusEvidenceBlock.status(true, 1, 0, 0, 12.0, 30));
        assertEquals("trajeto_proximo", BrGeoCensusEvidenceBlock.status(true, 1, 0, 0, 240.0, 30));
    }

    // ---------------------------------------------------------------- PAM history

    @Test
    void pamProductMatchesByNameOrPrefix() {
        List<Map<String, Object>> products = List.of(
                Map.of("product_id", 40124, "name", "Soja (em grão)"),
                Map.of("product_id", 40139, "name", "Café (em grão) Total"),
                Map.of("product_id", 40140, "name", "Café (em grão) Arábica"),
                Map.of("product_id", 40122, "name", "Milho (em grão)"),
                Map.of("product_id", 2713, "name", "Cana-de-açúcar"));
        assertEquals(40124, BrGeoCropProductionHistoryBlock.matchProduct(products, "soja").get("product_id"));
        assertEquals(40124, BrGeoCropProductionHistoryBlock.matchProduct(products, "SOJA (EM GRAO)").get("product_id"));
        assertEquals(40139, BrGeoCropProductionHistoryBlock.matchProduct(products, "Café").get("product_id"));
        assertEquals(40140, BrGeoCropProductionHistoryBlock.matchProduct(products, "cafe em grao arabica").get("product_id"));
        assertEquals(2713, BrGeoCropProductionHistoryBlock.matchProduct(products, "cana de acucar").get("product_id"));
        assertNull(BrGeoCropProductionHistoryBlock.matchProduct(products, "Trigo"));
        assertNull(BrGeoCropProductionHistoryBlock.matchProduct(products, " "));
    }

    @Test
    void pamSeriesHasOneItemPerYearAndDashIsZero() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(row(2023, 216, 1000.0, null, "Hectares"));
        rows.add(row(2023, 214, 3500.0, null, "Toneladas"));
        rows.add(row(2022, 216, null, "-", "Hectares"));
        rows.add(row(2022, 215, null, "X", "Mil Reais"));
        List<Map<String, Object>> series = BrGeoCropProductionHistoryBlock.series(rows);
        assertEquals(2, series.size());
        assertEquals(2022, series.get(0).get("year"));
        assertEquals(0.0, series.get(0).get("harvestedHa"));
        assertNull(series.get(0).get("valueThousandBrl"));
        assertEquals(3500.0, series.get(1).get("quantity"));
        assertEquals("Toneladas", series.get(1).get("unit"));
    }

    private static Map<String, Object> row(int year, int variable, Double value, String flag, String unit) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("year", year);
        row.put("variable_id", variable);
        row.put("value", value);
        row.put("value_flag", flag);
        row.put("unit", unit);
        return row;
    }

    @Test
    void yearChartIsAPng() throws Exception {
        byte[] png = new YearBarChart(false).render(List.of(new YearBarChart.Bar(2020, 10.0), new YearBarChart.Bar(2021, null),
                new YearBarChart.Bar(2022, 2500.0)), 400, 200);
        assertEquals(400, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
        assertEquals("3k", YearBarChart.label(3000, 1000));
        assertEquals("500", YearBarChart.label(500, 500));
        assertEquals("0.5", YearBarChart.label(0.5, 0.5));
    }

    // ---------------------------------------------------------------- ZARC

    @Test
    void zarcRiskFallsBackToTheNameOnlyForRowsWithoutCode() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(zarcRow("5107800", "Santo Antônio do Leverger"));
        rows.add(zarcRow(null, "Santo Antonio de Leverger"));
        rows.add(zarcRow(null, "Cuiabá"));
        assertEquals(1, BrGeoZarcRiskBlock.ofMunicipality(rows, "5107800", null).size());
        assertEquals(List.of(), BrGeoZarcRiskBlock.ofMunicipality(rows, "5103403", null));
        assertEquals("Cuiabá", BrGeoZarcRiskBlock.ofMunicipality(rows, "5103403", "CUIABA").get(0).get("municipality"));
        assertEquals(1, BrGeoZarcRiskBlock.ofMunicipality(rows, "5107800", "Santo Antônio de Leverger").size());
    }

    private static Map<String, Object> zarcRow(String cdMun, String name) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("cdMun", cdMun);
        row.put("municipality", name);
        return row;
    }

    @Test
    void cultivarCropStateAndAttributes() {
        assertTrue(BrGeoZarcCultivarsBlock.cropMatches("SOJA - Glycine max (L.) Merr.", "soja"));
        assertTrue(BrGeoZarcCultivarsBlock.cropMatches("ALGODÃO", "algodao"));
        assertTrue(BrGeoZarcCultivarsBlock.cropMatches("MILHO (2ª safra)", "milho"));
        assertFalse(BrGeoZarcCultivarsBlock.cropMatches("SOJA", "sorgo"));

        assertEquals("MT", BrGeoZarcCultivarsBlock.uf("Mato Grosso", null));
        assertEquals("AC", BrGeoZarcCultivarsBlock.uf(null, 1200401));
        assertThrows(IllegalArgumentException.class, () -> BrGeoZarcCultivarsBlock.uf(null, null));
        assertThrows(IllegalArgumentException.class, () -> BrGeoZarcCultivarsBlock.uf("XX", null));

        Map<String, Object> soy = BrGeoZarcCultivarsBlock.cultivar(Map.of("rnc", "10559", "cultivar", "BRS Tracajá",
                "adaptation_region", "Macrorregião 5", "attributes",
                "{\"Grupo de maturidade relativa\": \"9,2\", \"Nº médio de dias da emergência à maturação plena\": \"116\"}"));
        assertEquals("9,2", soy.get("group"));
        assertNull(soy.get("cycle"));
        assertEquals("116", ((Map<?, ?>) soy.get("attributes")).get("Nº médio de dias da emergência à maturação plena"));

        Map<String, Object> cotton = BrGeoZarcCultivarsBlock.cultivar(Map.of("rnc", "1", "cultivar", "X", "adaptation_region", "R",
                "attributes", "{\"Ciclo\": \"Médio\"}"));
        assertEquals("Médio", cotton.get("cycle"));
        assertEquals(Map.of(), BrGeoZarcCultivarsBlock.attributes("not json"));
    }
}
