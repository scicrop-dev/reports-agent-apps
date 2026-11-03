package com.infinitestack.reports.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.blocks.brgeo.BrGeoCensusEvidenceBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoCropProductionHistoryBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoSoilProfilesBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoSoilUnitsBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoZarcCultivarsBlock;
import com.infinitestack.reports.blocks.brgeo.BrGeoZarcRiskBlock;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The SQL of the BR Geo soil, census, PAM history and ZARC cultivar Blocks, and the name fallback of
 * brgeo-zarc-risk, against synthetic tables with the columns br-geo-agent-app creates, in a disposable
 * PostGIS. Same rules as {@link SourceBlocksSyntheticDbTest}: runs only with REPORTS_TEST_DB_URL, is
 * skipped when any of the tables already exists, and drops what it created.
 */
class BrGeoNewBlocksSyntheticDbTest {

    static final List<String> TABLES = List.of("br_geo_agent_app_ibge_municipality", "br_geo_agent_app_ibge_pedology_unit",
            "br_geo_agent_app_ibge_pedology_point", "br_geo_agent_app_ibge_pedology_horizon", "br_geo_agent_app_ibge_pedology_analysis",
            "br_geo_agent_app_census2017_route", "br_geo_agent_app_census2017_cnefe", "br_geo_agent_app_ibge_pam_value",
            "br_geo_agent_app_ibge_pam_product", "br_geo_agent_app_zarc_risk", "br_geo_agent_app_zarc_cultivar");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static BlockQueries queries;
    static boolean created;

    @BeforeAll
    static void createTables() {
        String url = System.getenv("REPORTS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "REPORTS_TEST_DB_URL not set");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("REPORTS_TEST_DB_USER", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault("REPORTS_TEST_DB_PASSWORD", ""));
        dataSource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(dataSource);
        for (String table : TABLES) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'gold' AND table_name = ?",
                    Integer.class, table);
            assumeTrue(exists == 0, "gold." + table + " already exists: not a disposable database");
        }
        created = true;
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gold");
        String versioned = ", row_hash text NOT NULL DEFAULT 'h', valid_from timestamptz NOT NULL DEFAULT now(), valid_to timestamptz,"
                + " load_id bigint NOT NULL DEFAULT 1, closed_load_id bigint)";
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_municipality (id bigserial PRIMARY KEY, edition smallint NOT NULL,"
                + " cd_mun varchar(7) NOT NULL, nm_mun text, sigla_uf varchar(2), geom geometry(MultiPolygon, 4674)" + versioned);
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_pedology_unit (id bigserial PRIMARY KEY, edition smallint NOT NULL,"
                + " id1 bigint NOT NULL, cd_fcim text, nom_unidade text, cod_simbolo text, n_componentes smallint, legenda text, ordem text,"
                + " subordem text, grande_grupo text, subgrupos text, textura text, horizonte text, erosao text, pedregosidade text,"
                + " rochosidade text, relevo text, componente text, componente_cont1 text, componente_cont2 text, componente_cont3 text,"
                + " inclusao text, inclusao_cont1 text, inclusao_cont2 text, area_km2 double precision, leg_ordem text, legenda_2 text,"
                + " cd_ordem smallint, cd_legenda_2 smallint, geom geometry(MultiPolygon, 4674) NOT NULL" + versioned);
        jdbc.execute("CREATE INDEX ON gold.br_geo_agent_app_ibge_pedology_unit USING gist (geom)");
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_pedology_point (id bigserial PRIMARY KEY, edition smallint NOT NULL,"
                + " id1 bigint NOT NULL, cod_ponto integer, cd_fcim text, id_ponto text, tipo_ponto text, fonte text, ordem text, subordem text,"
                + " grande_grupo text, subgrupo text, textura text, horizonte text, saturacao_aluminio text, fase_erosao text,"
                + " fase_pedregosidade text, fase_rochosidade text, fase_vegetacao text, fase_relevo text, relevo text, relevo_regional text,"
                + " pedregosidade text, rochosidade text, drenagem text, erosao text, localizacao text, situacao_declive text, litologia text,"
                + " material_origem text, vegetacao_primaria text, uso text, horizontes text, geom_from_coordinates boolean NOT NULL DEFAULT false,"
                + " geom geometry(Point, 4674) NOT NULL" + versioned);
        // As in br-geo: the sheet's ID replaces the serial id column.
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_pedology_horizon (id integer NOT NULL, edition smallint NOT NULL,"
                + " cod_ponto integer NOT NULL, tipo text, horizonte text, limite_superior text, limite_inferior text,"
                + " limite_superior_cm smallint, limite_inferior_cm smallint, classe_textura text, cascalho text, transicao_topografia text,"
                + " transicao_nitidez text, consistencia_seco text, consistencia_umido text, plasticidade text, pegajosidade text,"
                + " cerosidade text, estrutura text, cor text" + versioned);
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_pedology_analysis (id integer NOT NULL, edition smallint NOT NULL,"
                + " cod_ponto integer NOT NULL, cod_horizonte integer, horizonte text, laboratorio text, areia_muito_grossa double precision,"
                + " areia_grossa double precision, areia_media double precision, areia_fina double precision, areia_muito_fina double precision,"
                + " areia_total double precision, silte double precision, argila double precision, attributes jsonb NOT NULL DEFAULT '{}'"
                + versioned);
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_census2017_route (id bigserial PRIMARY KEY, cd_uf varchar(2) NOT NULL,"
                + " cd_mun varchar(7) NOT NULL, route_index text NOT NULL, source_file text NOT NULL,"
                + " geom geometry(MultiLineString, 4674) NOT NULL" + versioned);
        jdbc.execute("CREATE UNIQUE INDEX ON gold.br_geo_agent_app_census2017_route (cd_mun, route_index) WHERE valid_to IS NULL");
        jdbc.execute("CREATE INDEX ON gold.br_geo_agent_app_census2017_route USING gist (geom)");
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_census2017_cnefe (id bigserial PRIMARY KEY, cd_uf varchar(2) NOT NULL,"
                + " cd_mun varchar(7) NOT NULL, record_id varchar(32) NOT NULL, cod_distrito varchar(2), cod_subdistrito varchar(2),"
                + " situacao smallint, nom_tipo_seglogr text, nom_titulo_seglogr text, nom_seglogr text, num_endereco text,"
                + " dsc_modificador text, altitude numeric(8,1), dsc_localidade text, cod_especie smallint, cep varchar(8),"
                + " geom geometry(Point, 4674) NOT NULL" + versioned);
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_pam_value (id bigserial PRIMARY KEY, table_id integer NOT NULL,"
                + " year smallint NOT NULL, cd_uf varchar(2) NOT NULL, cd_mun varchar(7) NOT NULL, variable_id integer NOT NULL,"
                + " product_id integer NOT NULL, value numeric, value_flag text, unit text" + versioned);
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_ibge_pam_product (id bigserial PRIMARY KEY, table_id integer NOT NULL,"
                + " classification_id integer NOT NULL, product_id integer NOT NULL, name text NOT NULL, level smallint" + versioned);
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_zarc_risk (id bigserial PRIMARY KEY, season text NOT NULL, crop text NOT NULL,"
                + " cropping_system text NOT NULL, climate_type text NOT NULL, uf varchar(2) NOT NULL, ordinance text NOT NULL,"
                + " ordinance_date date, cd_mun varchar(7), municipality text NOT NULL, cultivar_group text NOT NULL, soil text NOT NULL,"
                + " risk smallint[] NOT NULL" + versioned);
        jdbc.execute("CREATE INDEX ON gold.br_geo_agent_app_zarc_risk (season, crop, cropping_system, climate_type, uf)");
        jdbc.execute("CREATE TABLE gold.br_geo_agent_app_zarc_cultivar (id bigserial PRIMARY KEY, season text NOT NULL, crop text NOT NULL,"
                + " rnc text NOT NULL, cultivar text NOT NULL, uf varchar(2) NOT NULL, adaptation_region text NOT NULL,"
                + " variant varchar(32) NOT NULL, attributes jsonb NOT NULL" + versioned);
        jdbc.execute("CREATE INDEX ON gold.br_geo_agent_app_zarc_cultivar (season, crop)");
        queries = new BlockQueries(dataSource);

        // Rio Branco (with census routes) and a municipality far away without any.
        insertMunicipality(2025, "1200401", "Rio Branco", "AC", -68.5, -10.5, -67.5, -9.5);
        insertMunicipality(2025, "1200450", "Senador Guiomard", "AC", -67.5, -10.5, -67.0, -9.5);
        insertMunicipality(2025, "5107800", "Santo Antônio de Leverger", "MT", -50.0, -20.0, -49.0, -19.0);
    }

    @AfterAll
    static void dropTables() {
        if (created) for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS gold." + table);
        if (dataSource != null) dataSource.close();
    }

    static void insertMunicipality(int edition, String cdMun, String name, String uf, double w, double s, double e, double n) {
        jdbc.update("INSERT INTO gold.br_geo_agent_app_ibge_municipality (edition, cd_mun, nm_mun, sigla_uf, geom)"
                + " VALUES (?, ?, ?, ?, ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 4674)))", edition, cdMun, name, uf, w, s, e, n);
    }

    static Map<String, Object> run(ReportBlock block, Map<String, Object> inputs) throws Exception {
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) {
            Object value = out.get(port.name());
            if (port.required()) assertNotNull(value, block.definition().id() + "." + port.name());
            port.type().coerce(value);
        }
        assertEquals(block.definition().outputs().size(), out.size(), "only declared outputs");
        return out;
    }

    static Map<String, Object> box(double west, double south, double east, double north) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(west, south), List.of(east, south),
                List.of(east, north), List.of(west, north), List.of(west, south))));
    }

    /** About 1.1 x 1.1 km near Rio Branco. */
    static final Map<String, Object> AREA = box(-68.0, -10.0, -67.99, -9.99);

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    // ---------------------------------------------------------------- soil units

    @Test
    void soilUnitsSplitTheAreaAndFlagRestrictions() throws Exception {
        String insert = "INSERT INTO gold.br_geo_agent_app_ibge_pedology_unit (edition, id1, cod_simbolo, legenda, ordem, subordem,"
                + " grande_grupo, textura, relevo, pedregosidade, rochosidade, erosao, componente, componente_cont1, geom)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 4674)))";
        jdbc.update(insert, 2026, 1, "LVd1", "LATOSSOLO VERMELHO Distrófico", "LATOSSOLOS", "LATOSSOLOS VERMELHOS", "Distróficos",
                "argilosa", "plano e suave ondulado", null, null, null, "LATOSSOLO VERMELHO Distrófico típico", null, -68.1, -10.1, -67.995, -9.9);
        jdbc.update(insert, 2026, 2, "PVA5", "ARGISSOLO VERMELHO-AMARELO", "ARGISSOLOS", "ARGISSOLOS VERMELHO-AMARELOS", "Distróficos",
                "média/argilosa", "ondulado e forte ondulado", "pedregosa", null, "laminar ligeira", "ARGISSOLO ... relevo forte ondulad",
                "NEOSSOLO LITÓLICO ...", -67.995, -10.1, -67.9, -9.995);
        jdbc.update(insert, 2026, 3, "PVA5", "ARGISSOLO VERMELHO-AMARELO", "ARGISSOLOS", "ARGISSOLOS VERMELHO-AMARELOS", "Distróficos",
                "média/argilosa", "ondulado e forte ondulado", "pedregosa", null, "laminar ligeira", "ARGISSOLO ... relevo forte ondulad",
                "NEOSSOLO LITÓLICO ...", -67.995, -9.995, -67.9, -9.9);
        jdbc.update(insert, 2026, 4, "OXy", "ORGANOSSOLO", "ORGANOSSOLOS", null, null, null, "plano", null, null, null, null, null,
                -60.0, -10.0, -59.0, -9.0);
        // an older edition over the whole area is ignored
        jdbc.update(insert, 2025, 1, "RQo", "NEOSSOLO", "NEOSSOLOS", null, null, null, "plano", null, null, null, null, null,
                -69.0, -11.0, -67.0, -9.0);

        BrGeoSoilUnitsBlock block = new BrGeoSoilUnitsBlock(queries);
        Map<String, Object> out = run(block, Map.of("geometry", AREA));
        assertEquals(true, out.get("hasUnits"));
        assertEquals(2026, ((Number) out.get("edition")).intValue());
        assertEquals(2, out.get("count"));
        List<Map<String, Object>> units = list(out.get("units"));
        assertEquals(List.of("LVd1", "PVA5"), units.stream().map(u -> u.get("symbol")).sorted().toList());
        Map<String, Object> pva = units.stream().filter(u -> "PVA5".equals(u.get("symbol"))).findFirst().orElseThrow();
        assertEquals(50.0, (Double) pva.get("percent"), 0.5);
        assertEquals(List.of("ARGISSOLO ... relevo forte ondulad", "NEOSSOLO LITÓLICO ..."), pva.get("components"));
        assertEquals(true, pva.get("componentsCut"));
        assertEquals(List.of("STEEP_RELIEF", "STONY"), pva.get("restrictions"));
        assertEquals(2, list(out.get("byOrder")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) out.get("flags");
        assertEquals(true, flags.get("steepRelief"));
        assertEquals(true, flags.get("stony"));
        assertEquals(false, flags.get("organosol"));
        assertEquals(true, out.get("hasRestrictions"));
        assertEquals(2, list(out.get("restrictions")).size());
        double areaHa = (Double) out.get("areaHa");
        assertTrue(areaHa > 110 && areaHa < 130, String.valueOf(areaHa));

        Map<String, Object> one = run(block, Map.of("geometry", AREA, "limit", 1));
        assertEquals(1, list(one.get("units")).size());
        assertEquals(true, one.get("truncated"));

        Map<String, Object> sea = run(block, Map.of("geometry", box(-30.0, -20.0, -29.99, -19.99)));
        assertEquals(false, sea.get("hasUnits"));
        assertNull(sea.get("mainOrder"));
        assertEquals(false, sea.get("hasRestrictions"));
    }

    // ---------------------------------------------------------------- soil profiles

    @Test
    void soilProfilesNearestFirstWithWeightedGranulometry() throws Exception {
        String point = "INSERT INTO gold.br_geo_agent_app_ibge_pedology_point (edition, id1, cod_ponto, id_ponto, tipo_ponto, ordem,"
                + " subordem, grande_grupo, textura, drenagem, relevo, geom) VALUES (2026, ?, ?, ?, 'Perfil', ?, ?, ?, ?, ?, ?,"
                + " ST_SetSRID(ST_MakePoint(?, ?), 4674))";
        jdbc.update(point, 1, 101, "AC-01", "LATOSSOLOS", "LATOSSOLOS VERMELHOS", "Distróficos", "argilosa", "bem drenado", "plano", -67.995, -9.995);
        jdbc.update(point, 2, 102, "AC-02", "ARGISSOLOS", null, null, "média", "moderadamente drenado", "ondulado", -68.05, -9.995);
        jdbc.update(point, 3, 103, "AC-03", "GLEISSOLOS", null, null, null, "mal drenado", "plano", -68.5, -9.995);
        String horizon = "INSERT INTO gold.br_geo_agent_app_ibge_pedology_horizon (id, edition, cod_ponto, horizonte, limite_superior,"
                + " limite_inferior, limite_superior_cm, limite_inferior_cm, classe_textura) VALUES (?, 2026, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(horizon, 1001, 101, "Ap", "0", "20", 0, 20, "franco-argilosa");
        jdbc.update(horizon, 1002, 101, "Bw1", "20", "60", 20, 60, "argila");
        jdbc.update(horizon, 1003, 101, "Bw2", "60", "120+", 60, null, "argila");
        String analysis = "INSERT INTO gold.br_geo_agent_app_ibge_pedology_analysis (id, edition, cod_ponto, cod_horizonte, areia_total,"
                + " areia_muito_grossa, areia_grossa, areia_media, areia_fina, areia_muito_fina, silte, argila)"
                + " VALUES (?, 2026, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(analysis, 5001, 101, 1001, 600.0, null, null, null, null, null, 200.0, 200.0);
        // no total sand: the five fractions add up to 300
        jdbc.update(analysis, 5002, 101, 1002, null, 50.0, 50.0, 50.0, 100.0, 50.0, 200.0, 500.0);
        jdbc.update(analysis, 5003, 101, 1003, 100.0, null, null, null, null, null, 100.0, 800.0);

        BrGeoSoilProfilesBlock block = new BrGeoSoilProfilesBlock(queries);
        Map<String, Object> out = run(block, Map.of("geometry", AREA));
        assertEquals(true, out.get("found"));
        assertEquals(2, out.get("count"));
        List<Map<String, Object>> profiles = list(out.get("profiles"));
        Map<String, Object> first = profiles.get(0);
        assertEquals("AC-01", first.get("code"));
        assertEquals(true, first.get("inside"));
        assertEquals(0.0, first.get("distanceKm"));
        assertEquals("bem drenado", first.get("drainage"));
        assertEquals(35.0, first.get("clay0_40Pct"));
        assertEquals(45.0, first.get("sand0_40Pct"));
        assertEquals(20.0, first.get("silt0_40Pct"));
        assertEquals(40, first.get("covered0_40Cm"));
        assertEquals(3, list(first.get("horizons")).size());
        Map<String, Object> second = profiles.get(1);
        assertEquals("AC-02", second.get("code"));
        assertEquals(false, second.get("hasLabData"));
        assertNull(second.get("clay0_40Pct"));
        double km = (Double) second.get("distanceKm");
        assertTrue(km > 4 && km < 7, String.valueOf(km));

        Map<String, Object> wide = run(block, Map.of("geometry", AREA, "radiusKm", 80, "limit", 10));
        assertEquals(3, wide.get("count"));
        Map<String, Object> one = run(block, Map.of("geometry", AREA, "limit", 1));
        assertEquals(1, one.get("count"));
        Map<String, Object> none = run(block, Map.of("geometry", box(-30.0, -20.0, -29.99, -19.99)));
        assertEquals(false, none.get("found"));
    }

    // ---------------------------------------------------------------- census

    @Test
    void censusEvidenceStatuses() throws Exception {
        String route = "INSERT INTO gold.br_geo_agent_app_census2017_route (cd_uf, cd_mun, route_index, source_file, geom)"
                + " VALUES ('12', ?, ?, 'trajetos_1200401_RIO_BRANCO.kml', ST_Multi(ST_GeomFromText(?, 4674)))";
        // crosses AREA from west to east along its middle (about 1.1 km inside)
        jdbc.update(route, "1200401", "1", "LINESTRING(-68.01 -9.995, -67.98 -9.995)");
        jdbc.update(route, "1200401", "2", "LINESTRING(-68.3 -10.3, -68.29 -10.3)");
        String cnefe = "INSERT INTO gold.br_geo_agent_app_census2017_cnefe (cd_uf, cd_mun, record_id, situacao, nom_tipo_seglogr,"
                + " nom_seglogr, num_endereco, dsc_localidade, cod_especie, geom) VALUES ('12', '1200401', ?, 2, 'RAMAL', ?, 'SN', ?, 3,"
                + " ST_SetSRID(ST_MakePoint(?, ?), 4674))";
        jdbc.update(cnefe, "a", "DO CAPATARA", "COLÔNIA SÃO JOÃO", -67.995, -9.993);
        jdbc.update(cnefe, "b", "DO CAPATARA", "COLÔNIA SÃO JOÃO", -67.987, -9.995);
        jdbc.update(cnefe, "c", "BOM FUTURO", "SERINGAL", -67.9, -9.995);

        BrGeoCensusEvidenceBlock block = new BrGeoCensusEvidenceBlock(queries);
        Map<String, Object> inside = run(block, Map.of("geometry", AREA));
        assertEquals("trajeto_interno", inside.get("status"));
        assertEquals(true, inside.get("routeInside"));
        assertEquals(true, inside.get("dataAvailable"));
        double km = ((Number) inside.get("insideKm")).doubleValue();
        assertTrue(km > 1.0 && km < 1.2, String.valueOf(km));
        assertEquals(0.0, ((Number) inside.get("minDistanceM")).doubleValue());
        assertEquals(1, inside.get("routesCrossing"));
        assertEquals(1, inside.get("cnefeInside"));
        assertEquals(2, inside.get("cnefeNearCount"));
        List<Map<String, Object>> near = list(inside.get("cnefeNear"));
        assertEquals(true, near.get(0).get("inside"));
        assertEquals("RAMAL DO CAPATARA", near.get(0).get("address"));
        assertEquals(1200401L, near.get(0).get("municipalityIbge"));
        assertEquals(false, inside.get("truncated"));

        // the route runs along the southern border of this area
        Map<String, Object> edge = run(block, Map.of("geometry", box(-68.0, -9.995, -67.99, -9.985)));
        assertEquals("trajeto_de_borda", edge.get("status"), String.valueOf(edge));

        // 1.6 km north of the route
        Map<String, Object> far = run(block, Map.of("geometry", box(-68.0, -9.98, -67.99, -9.97)));
        assertEquals("trajeto_nao_encontrado", far.get("status"));
        assertEquals(false, far.get("routeNear"));
        assertNull(far.get("minDistanceM"));
        Map<String, Object> nearBy = run(block, Map.of("geometry", box(-68.0, -9.98, -67.99, -9.97), "bufferM", 2000));
        assertEquals("trajeto_proximo", nearBy.get("status"));
        double distance = ((Number) nearBy.get("minDistanceM")).doubleValue();
        assertTrue(distance > 1500 && distance < 1800, String.valueOf(distance));

        // a municipality without routes loaded
        Map<String, Object> unavailable = run(block, Map.of("geometry", box(-49.6, -19.6, -49.59, -19.59)));
        assertEquals("dados_indisponiveis", unavailable.get("status"));
        assertEquals(false, unavailable.get("dataAvailable"));
    }

    // ---------------------------------------------------------------- PAM history

    @Test
    void cropProductionHistoryTakesTheLastYears() throws Exception {
        jdbc.update("INSERT INTO gold.br_geo_agent_app_ibge_pam_product (table_id, classification_id, product_id, name) VALUES"
                + " (5457, 782, 0, 'Total'), (5457, 782, 40124, 'Soja (em grão)'), (5457, 782, 40122, 'Milho (em grão)')");
        String value = "INSERT INTO gold.br_geo_agent_app_ibge_pam_value (table_id, year, cd_uf, cd_mun, variable_id, product_id, value,"
                + " value_flag, unit) VALUES (5457, ?, '12', '1200401', ?, 40124, ?, ?, ?)";
        for (int year = 2014; year <= 2024; year++) {
            jdbc.update(value, year, 8331, 100.0 * (year - 2013), null, "Hectares");
            jdbc.update(value, year, 216, year == 2022 ? null : 90.0 * (year - 2013), year == 2022 ? "-" : null, "Hectares");
            jdbc.update(value, year, 214, 300.0 * (year - 2013), null, "Toneladas");
            jdbc.update(value, year, 112, 3300.0, null, "Quilogramas por Hectare");
            jdbc.update(value, year, 215, year == 2021 ? null : 500.0, year == 2021 ? "X" : null, "Mil Reais");
        }

        BrGeoCropProductionHistoryBlock block = new BrGeoCropProductionHistoryBlock(queries);
        Map<String, Object> out = run(block, Map.of("municipalityIbge", 1200401, "product", "soja", "years", 5));
        assertEquals(true, out.get("found"));
        assertEquals("Soja (em grão)", out.get("product"));
        assertEquals(5, out.get("count"));
        assertEquals(2020, out.get("fromYear"));
        assertEquals(2024, out.get("toYear"));
        assertEquals("Toneladas", out.get("unit"));
        assertEquals("AC", out.get("uf"));
        List<Map<String, Object>> series = list(out.get("series"));
        assertEquals(0.0, series.get(2).get("harvestedHa"));
        assertNull(series.get(1).get("valueThousandBrl"));
        assertEquals(3300.0, series.get(4).get("yieldKgHa"));
        assertTrue(((String) out.get("chart")).startsWith("data:image/png;base64,"));

        Map<String, Object> all = run(block, Map.of("municipalityIbge", 1200401, "product", "Soja (em grão)", "chartVariable", "yieldKgHa"));
        assertEquals(10, all.get("count"));
        assertEquals("yieldKgHa", all.get("chartVariable"));

        Map<String, Object> milho = run(block, Map.of("municipalityIbge", 1200401, "product", "milho"));
        assertEquals(false, milho.get("found"));
        assertEquals("Milho (em grão)", milho.get("product"));
        Map<String, Object> unknown = run(block, Map.of("municipalityIbge", 1200401, "product", "trigo"));
        assertEquals(false, unknown.get("found"));
        assertTrue(list(unknown.get("availableProducts")).size() > 0 || !((List<?>) unknown.get("availableProducts")).isEmpty());
    }

    // ---------------------------------------------------------------- ZARC

    @Test
    void zarcRiskFallsBackToTheMunicipalityName() throws Exception {
        String insert = "INSERT INTO gold.br_geo_agent_app_zarc_risk (season, crop, cropping_system, climate_type, uf, ordinance,"
                + " ordinance_date, cd_mun, municipality, cultivar_group, soil, risk)"
                + " VALUES ('2026/2027', 'Soja', 'Sequeiro', 'Não se aplica', ?, '193', DATE '2026-07-03', ?, ?, 'Grupo I', 'AD3', ?::smallint[])";
        String risk = "{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,20,20,20,20,20,20,20,20}";
        jdbc.update(insert, "AC", "1200401", "Rio Branco", risk);
        jdbc.update(insert, "AC", null, "SENADOR GUIOMARD", risk);
        jdbc.update(insert, "MT", null, "Santo Antonio do Leverger", risk);

        BrGeoZarcRiskBlock block = new BrGeoZarcRiskBlock(queries);
        Map<String, Object> byCode = run(block, Map.of("municipalityIbge", 1200401, "crop", "Soja"));
        assertEquals(true, byCode.get("found"));
        assertEquals("code", byCode.get("matchedBy"));
        assertEquals(1, byCode.get("count"));

        Map<String, Object> byName = run(block, Map.of("municipalityIbge", 1200450, "crop", "Soja"));
        assertEquals(true, byName.get("found"), String.valueOf(byName.get("message")));
        assertEquals("name", byName.get("matchedBy"));
        assertEquals("SENADOR GUIOMARD", byName.get("municipality"));
        assertEquals(1, byName.get("count"));

        // the ZARC spells it differently from the IBGE: no fuzzy match
        Map<String, Object> misspelled = run(block, Map.of("municipalityIbge", 5107800, "crop", "Soja"));
        assertEquals(false, misspelled.get("found"));
        assertNull(misspelled.get("matchedBy"));
    }

    @Test
    void zarcCultivarsOfTheLatestSeasonInTheState() throws Exception {
        String insert = "INSERT INTO gold.br_geo_agent_app_zarc_cultivar (season, crop, rnc, cultivar, uf, adaptation_region, variant,"
                + " attributes) VALUES (?, 'SOJA', ?, ?, ?, ?, ?, ?::jsonb)";
        String attrs = "{\"Grupo de maturidade relativa\": \"%s\", \"Potencial produtivo Kg/ha\": \"3298\"}";
        jdbc.update(insert, "2026/2027", "10559", "BRS Tracajá", "MT", "Macrorregião 4", "v1", String.format(attrs, "9,2"));
        jdbc.update(insert, "2026/2027", "10559", "BRS Tracajá", "MT", "Macrorregião 5", "v1", String.format(attrs, "9,2"));
        jdbc.update(insert, "2026/2027", "20001", "AS 3800", "MT", "Macrorregião 4", "v1", String.format(attrs, "8,0"));
        jdbc.update(insert, "2026/2027", "20001", "AS 3800", "PR", "Macrorregião 2", "v1", String.format(attrs, "8,0"));
        jdbc.update(insert, "2025/2026", "30001", "Old One", "MT", "Macrorregião 4", "v1", String.format(attrs, "7,0"));

        BrGeoZarcCultivarsBlock block = new BrGeoZarcCultivarsBlock(queries);
        Map<String, Object> out = run(block, Map.of("crop", "soja", "uf", "mt"));
        assertEquals(true, out.get("found"));
        assertEquals("2026/2027", out.get("season"));
        assertEquals("MT", out.get("uf"));
        assertEquals(3, out.get("count"));
        assertEquals(2, out.get("distinctCultivars"));
        assertEquals(List.of("Macrorregião 4", "Macrorregião 5"), out.get("adaptationRegions"));
        List<Map<String, Object>> cultivars = list(out.get("cultivars"));
        assertEquals("AS 3800", cultivars.get(0).get("cultivar"));
        assertEquals("8,0", cultivars.get(0).get("group"));

        Map<String, Object> limited = run(block, Map.of("crop", "SOJA", "municipalityIbge", 5107800, "limit", 1));
        assertEquals(1, list(limited.get("cultivars")).size());
        assertEquals(true, limited.get("truncated"));

        Map<String, Object> old = run(block, Map.of("crop", "soja", "uf", "MT", "season", "2025/2026"));
        assertEquals(1, old.get("count"));

        Map<String, Object> none = run(block, Map.of("crop", "soja", "uf", "RS"));
        assertEquals(false, none.get("found"));
        assertEquals(List.of("SOJA"), none.get("availableCrops"));
        Map<String, Object> unknown = run(block, Map.of("crop", "trigo", "uf", "MT"));
        assertEquals(false, unknown.get("found"));
        assertFalse(((String) unknown.get("message")).isEmpty());
        assertTrue(new HashMap<>(unknown).containsKey("cultivars"));
    }
}
