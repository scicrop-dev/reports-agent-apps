package com.infinitestack.reports.blocks.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.blocks.car.ReportAreaBlock;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.zaxxer.hikari.HikariDataSource;

/**
 * report-area from a GIS dataset of the Data Library, and the dataset / feature lists of the run form,
 * against {@code bronze.is_geo_ref*} created with the DDL of infinitestack-back (GisTableInitializer) in a
 * disposable PostGIS. Runs only with REPORTS_TEST_DB_URL (+ _USER, _PASSWORD) and only when the tables do
 * not exist yet; it drops what it created.
 */
class GisAreasSyntheticDbTest {

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
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        queries = new BlockQueries(dataSource);
        // Before the tables exist: no GIS on the instance.
        GisAreas gis = new GisAreas(queries);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'bronze'"
                + " AND table_name IN ('is_geo_ref', 'is_geo_ref_data')", Integer.class);
        assumeTrue(exists == 0, "bronze.is_geo_ref* already exist: not a disposable database");
        assertEquals(false, gis.datasets().get("available"));
        assertThrows(IllegalArgumentException.class, () -> gis.area("Fazendas", null));

        created = true;
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS bronze");
        jdbc.execute("CREATE TABLE bronze.is_geo_ref (geo_id serial PRIMARY KEY, geo_name text NOT NULL UNIQUE,"
                + " geo_attributes jsonb NULL, geo_ts timestamp NOT NULL DEFAULT current_timestamp)");
        jdbc.execute("CREATE TABLE bronze.is_geo_ref_data (geo_data_id serial PRIMARY KEY,"
                + " geo_id int NOT NULL REFERENCES bronze.is_geo_ref(geo_id) ON DELETE CASCADE, geo_data geometry(Geometry, 4326),"
                + " geo_data_attributes jsonb NULL, geo_data_ts timestamp NOT NULL DEFAULT current_timestamp)");
        jdbc.update("INSERT INTO bronze.is_geo_ref (geo_id, geo_name, geo_attributes) VALUES (1, 'Fazendas', ?::jsonb), (2, 'Poços', NULL)",
                "{\"category\":{\"id\":\"1\",\"slug\":\"propriedades\",\"title\":\"Propriedades\"},\"description\":\"Talhões\","
                        + "\"feature_count\":3,\"area_km2\":12.5}");
        jdbc.update("INSERT INTO bronze.is_geo_ref_data (geo_data_id, geo_id, geo_data, geo_data_attributes) VALUES"
                + " (10, 1, ST_MakeEnvelope(-52.21, -12.64, -52.17, -12.61, 4326), '{\"NOME\":\"Talhão 1\"}'),"
                + " (11, 1, ST_Multi(ST_MakeEnvelope(-52.19, -12.64, -52.15, -12.61, 4326)), '{\"area\":3}'),"
                + " (12, 1, ST_SetSRID(ST_MakePoint(-52.18, -12.62), 4326), '{\"name\":\"sede\"}'),"
                + " (20, 2, ST_SetSRID(ST_MakePoint(-52.0, -12.0), 4326), NULL)");
    }

    @AfterAll
    static void dropTables() {
        if (created) jdbc.execute("DROP TABLE IF EXISTS bronze.is_geo_ref_data, bronze.is_geo_ref");
        if (dataSource != null) dataSource.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsDatasetsAndFeatures() {
        GisAreas gis = new GisAreas(queries);
        Map<String, Object> list = gis.datasets();
        assertEquals(true, list.get("available"));
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) list.get("datasets");
        assertEquals(List.of("Fazendas", "Poços"), datasets.stream().map(d -> d.get("name")).toList());
        assertEquals("Propriedades", datasets.get(0).get("category"));
        assertEquals(2L, datasets.get(0).get("polygonCount"));
        assertEquals(0L, datasets.get(1).get("polygonCount"));

        Map<String, Object> features = gis.features("fazendas");
        List<Map<String, Object>> items = (List<Map<String, Object>>) features.get("features");
        assertEquals(List.of("Talhão 1", "Feature 11", "sede"), items.stream().map(f -> f.get("label")).toList());
        assertEquals(false, items.get(2).get("polygon"));
        assertFalse((Boolean) features.get("truncated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportAreaTakesADatasetOrOneFeature() throws Exception {
        ReportAreaBlock block = new ReportAreaBlock(queries, new GisAreas(queries));
        Map<String, Object> all = run(block, Map.of("gisDataset", "Fazendas"));
        assertEquals("gis", all.get("source"));
        assertEquals(false, all.get("isCar"));
        assertEquals("Fazendas", all.get("label"));
        assertEquals(1, all.get("gisDatasetId"));
        assertEquals(2L, all.get("gisPolygons"));
        assertNull(all.get("codImovel"));

        Map<String, Object> one = run(block, Map.of("gisDataset", "1", "gisFeature", 10));
        assertEquals("Talhão 1 (Fazendas)", one.get("label"));
        assertEquals(10, one.get("gisFeatureId"));
        double merged = (Double) all.get("areaHa");
        double single = (Double) one.get("areaHa");
        // The two overlapping polygons are merged, not added; the point is ignored.
        assertTrue(merged > single && merged < 2 * single, merged + " vs " + single);
        assertEquals(-52.15, ((Map<String, Object>) all.get("bbox")).get("maxLon"));

        Map<String, Object> named = new HashMap<>(Map.of("gisDataset", "Fazendas", "gisFeature", 11, "name", "Gleba B"));
        assertEquals("Gleba B", run(block, named).get("label"));

        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("gisDataset", "Poços"), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("gisDataset", "Fazendas", "gisFeature", 12), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("gisDataset", "Fazendas", "gisFeature", 99), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("gisDataset", "Nada"), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("gisFeature", 10), null));
        assertThrows(IllegalArgumentException.class, () -> block.execute(Map.of("gisDataset", "Fazendas", "codImovel", "MT-1"), null));
    }

    private static Map<String, Object> run(ReportAreaBlock block, Map<String, Object> inputs) throws Exception {
        Map<String, Object> out = block.execute(inputs, null);
        for (BlockPort port : block.definition().outputs()) port.type().coerce(out.get(port.name()));
        assertEquals(block.definition().outputs().size(), out.size(), "only declared outputs");
        return out;
    }
}
