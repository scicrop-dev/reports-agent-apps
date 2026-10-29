package com.infinitestack.reports.blocks.car;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.infinitestack.reports.blocks.gis.GisAreas;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ReportBlock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The CAR Blocks against synthetic tables with the structure car-agent-app creates
 * ({@code skillslab/car-ingest/car_ingest/schema.py}, only the columns read) and the states its
 * loader leaves: versions closed on a change, a property removed from the SICAR (last version
 * closed on {@code removido_em}), feature sets closed and replaced, hydrography with no declared
 * area, rows set aside in {@code descartado}. Runs only with REPORTS_TEST_DB_URL (+ _USER,
 * _PASSWORD) on a disposable PostGIS, e.g.
 * {@code docker run -d --rm -p 55443:5432 -e POSTGRES_PASSWORD=pg postgis/postgis:16-3.4}. If any of
 * the CAR tables already exists it is skipped instead of touching them, and it drops what it created.
 */
class CarBlocksSyntheticDbTest {

    static final List<String> TABLES = List.of("car_agent_app_descartado", "car_agent_app_feicao", "car_agent_app_imovel_versao",
            "car_agent_app_imovel_geometria", "car_agent_app_imovel", "car_agent_app_carga", "car_agent_app_municipio",
            "car_agent_app_tema", "car_agent_app_camada", "car_agent_app_condicao", "car_agent_app_tipo_imovel",
            "car_agent_app_status_cadastro");

    /** Active, two versions (the drawing grew), declared area far from the drawn one. */
    static final String ACTIVE = "MT-5107925-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    /** Left the SICAR on 2026-03-01. */
    static final String REMOVED = "MT-5107925-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    /** Active neighbour overlapping half of ACTIVE. */
    static final String NEIGHBOUR = "MT-5107925-CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
    static final String MISSING = "MT-5107925-DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";

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
        jdbc.execute("CREATE TABLE gold.car_agent_app_status_cadastro (codigo varchar(2) PRIMARY KEY, descricao text NOT NULL)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_tipo_imovel (codigo varchar(3) PRIMARY KEY, descricao text NOT NULL)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_condicao (id smallserial PRIMARY KEY, descricao text NOT NULL UNIQUE)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_camada (codigo text PRIMARY KEY, nome text NOT NULL, ordem smallint NOT NULL)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_tema (codigo text PRIMARY KEY, nome text NOT NULL, camada text NOT NULL,"
                + " agregado boolean NOT NULL DEFAULT false)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_municipio (cod_ibge integer PRIMARY KEY, nome text NOT NULL, uf varchar(2) NOT NULL,"
                + " geom geometry(MultiPolygon, 4674))");
        jdbc.execute("CREATE TABLE gold.car_agent_app_carga (id bigserial PRIMARY KEY, uf varchar(2) NOT NULL, camada text NOT NULL,"
                + " data_publicacao date NOT NULL, sha256 varchar(64) NOT NULL, bytes bigint NOT NULL,"
                + " status text NOT NULL DEFAULT 'carregando' CHECK (status IN ('carregando', 'concluida', 'falhou')),"
                + " iniciada_em timestamptz DEFAULT now(), concluida_em timestamptz, UNIQUE (uf, camada, data_publicacao))");
        jdbc.execute("CREATE TABLE gold.car_agent_app_imovel (id bigserial PRIMARY KEY, cod_imovel varchar(43) NOT NULL UNIQUE,"
                + " uf varchar(2) GENERATED ALWAYS AS (left(cod_imovel, 2)) STORED,"
                + " cod_municipio integer GENERATED ALWAYS AS (substr(cod_imovel, 4, 7)::integer) STORED,"
                + " primeira_carga_id bigint NOT NULL, removido_em date)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_imovel_geometria (id bigserial PRIMARY KEY, imovel_id bigint NOT NULL,"
                + " geom_hash varchar(32) NOT NULL, geom geometry(MultiPolygon, 4674) NOT NULL, area_calc_ha numeric(16,4),"
                + " carga_id bigint NOT NULL, UNIQUE (imovel_id, geom_hash))");
        jdbc.execute("CREATE INDEX idx_car_agent_app_imovel_geometria_geom ON gold.car_agent_app_imovel_geometria USING gist (geom)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_imovel_versao (id bigserial PRIMARY KEY, imovel_id bigint NOT NULL,"
                + " cod_imovel varchar(43) NOT NULL, valido_de date NOT NULL, valido_ate date, ind_status varchar(2) NOT NULL,"
                + " ind_tipo varchar(3) NOT NULL, condicao_id smallint, municipio_sicar text, mod_fiscal numeric(14,4),"
                + " num_area numeric(16,4), dat_criacao date, dat_atualizacao date, geometria_id bigint NOT NULL,"
                + " hash varchar(32) NOT NULL, carga_id bigint NOT NULL, UNIQUE (imovel_id, valido_de))");
        jdbc.execute("CREATE UNIQUE INDEX idx_car_agent_app_imovel_versao_vigente ON gold.car_agent_app_imovel_versao (imovel_id)"
                + " WHERE valido_ate IS NULL");
        jdbc.execute("CREATE TABLE gold.car_agent_app_feicao (id bigserial PRIMARY KEY, camada text NOT NULL, imovel_id bigint NOT NULL,"
                + " cod_imovel varchar(43) NOT NULL, uf varchar(2) GENERATED ALWAYS AS (left(cod_imovel, 2)) STORED,"
                + " cod_tema text NOT NULL, num_area numeric(16,4), geom geometry(MultiPolygon, 4674) NOT NULL,"
                + " conjunto_hash varchar(32) NOT NULL, valido_de date NOT NULL, valido_ate date, carga_id bigint NOT NULL)");
        jdbc.execute("CREATE TABLE gold.car_agent_app_descartado (id bigserial PRIMARY KEY, carga_id bigint NOT NULL, uf varchar(2) NOT NULL,"
                + " camada text NOT NULL, cod_imovel text,"
                + " motivo text NOT NULL CHECK (motivo IN ('duplicado', 'geometria_invalida', 'orfao')),"
                + " detalhe text, atributos text, geom geometry(MultiPolygon, 4674), criado_em timestamptz DEFAULT now())");
        seed();
        queries = new BlockQueries(dataSource);
    }

    @AfterAll
    static void dropTables() {
        if (created) for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS gold." + table);
        if (dataSource != null) dataSource.close();
    }

    static String box(double west, double south, double east, double north) {
        return "ST_Multi(ST_MakeEnvelope(" + west + ", " + south + ", " + east + ", " + north + ", 4674))";
    }

    static void seed() {
        jdbc.execute("INSERT INTO gold.car_agent_app_status_cadastro VALUES ('AT', 'Ativo'), ('PE', 'Pendente'), ('SU', 'Suspenso'),"
                + " ('CA', 'Cancelado')");
        jdbc.execute("INSERT INTO gold.car_agent_app_tipo_imovel VALUES ('IRU', 'Imóvel rural'), ('AST', 'Assentamento')");
        jdbc.execute("INSERT INTO gold.car_agent_app_condicao (id, descricao) VALUES (1, 'Aguardando análise')");
        jdbc.execute("INSERT INTO gold.car_agent_app_camada VALUES ('AREA_IMOVEL', 'Área do imóvel', 0), ('APPS', 'Área de Preservação Permanente', 1),"
                + " ('VEGETACAO_NATIVA', 'Remanescente de vegetação nativa', 2), ('AREA_CONSOLIDADA', 'Área consolidada', 3),"
                + " ('AREA_POUSIO', 'Área de pousio', 4), ('HIDROGRAFIA', 'Hidrografia', 5), ('USO_RESTRITO', 'Uso restrito', 6),"
                + " ('SERVIDAO_ADMINISTRATIVA', 'Servidão administrativa', 7), ('RESERVA_LEGAL', 'Reserva legal', 8)");
        jdbc.execute("INSERT INTO gold.car_agent_app_tema VALUES ('APP_TOTAL', 'APP total', 'APPS', true),"
                + " ('APP_RIO_ATE_10', 'APP de rio até 10 m', 'APPS', false), ('RIO_ATE_10', 'Rio até 10 m', 'HIDROGRAFIA', false),"
                + " ('ARL_PROPOSTA', 'Reserva legal proposta', 'RESERVA_LEGAL', false), ('USO_RESTRITO', 'Uso restrito', 'USO_RESTRITO', false)");
        jdbc.execute("INSERT INTO gold.car_agent_app_municipio (cod_ibge, nome, uf) VALUES (5107925, 'Sorriso', 'MT')");
        // loads of MT: the failed HIDROGRAFIA one must not count
        jdbc.execute("INSERT INTO gold.car_agent_app_carga (id, uf, camada, data_publicacao, sha256, bytes, status, concluida_em) VALUES"
                + " (1, 'MT', 'AREA_IMOVEL', '2025-01-01', 'x', 1, 'concluida', '2025-01-02 10:00:00-03'),"
                + " (2, 'MT', 'AREA_IMOVEL', '2025-06-01', 'x', 1, 'concluida', '2025-06-02 10:00:00-03'),"
                + " (3, 'MT', 'AREA_IMOVEL', '2026-03-01', 'x', 1, 'concluida', '2026-03-02 10:00:00-03'),"
                + " (4, 'MT', 'APPS', '2026-02-01', 'x', 1, 'concluida', '2026-02-02 10:00:00-03'),"
                + " (5, 'MT', 'HIDROGRAFIA', '2026-01-10', 'x', 1, 'concluida', '2026-01-11 10:00:00-03'),"
                + " (6, 'MT', 'HIDROGRAFIA', '2026-04-01', 'x', 1, 'falhou', NULL),"
                + " (7, 'MT', 'RESERVA_LEGAL', '2026-03-01', 'x', 1, 'concluida', '2026-03-03 10:00:00-03'),"
                + " (8, 'GO', 'AREA_IMOVEL', '2026-05-01', 'x', 1, 'concluida', '2026-05-02 10:00:00-03')");
        jdbc.execute("INSERT INTO gold.car_agent_app_imovel (id, cod_imovel, primeira_carga_id, removido_em) VALUES"
                + " (1, '" + ACTIVE + "', 1, NULL), (2, '" + REMOVED + "', 1, '2026-03-01'), (3, '" + NEIGHBOUR + "', 1, NULL)");
        jdbc.execute("INSERT INTO gold.car_agent_app_imovel_geometria (id, imovel_id, geom_hash, geom, area_calc_ha, carga_id) VALUES"
                + " (11, 1, 'g11', " + box(-52.0, -12.0, -51.995, -11.995) + ", NULL, 1),"
                + " (12, 1, 'g12', " + box(-52.0, -12.0, -51.99, -11.99) + ", NULL, 2),"
                + " (21, 2, 'g21', " + box(-52.005, -12.0, -51.998, -11.99) + ", NULL, 1),"
                + " (31, 3, 'g31', " + box(-51.995, -12.0, -51.985, -11.99) + ", NULL, 1)");
        // as the loader does: area_calc_ha = ST_Area(geom::geography) / 1e4
        jdbc.execute("UPDATE gold.car_agent_app_imovel_geometria SET area_calc_ha = ST_Area(geom::geography) / 1e4");
        // ACTIVE: closed v1 and current v2 (declared 200 ha, drawn ~120 ha); REMOVED: last version closed on removido_em
        jdbc.execute("INSERT INTO gold.car_agent_app_imovel_versao (imovel_id, cod_imovel, valido_de, valido_ate, ind_status, ind_tipo,"
                + " condicao_id, mod_fiscal, num_area, dat_criacao, dat_atualizacao, geometria_id, hash, carga_id) VALUES"
                + " (1, '" + ACTIVE + "', '2025-01-01', '2025-06-01', 'PE', 'IRU', 1, 2, 30, '2020-01-01', '2024-12-01', 11, 'h1', 1),"
                + " (1, '" + ACTIVE + "', '2025-06-01', NULL, 'AT', 'IRU', 1, 2, 200, '2020-01-01', '2025-05-01', 12, 'h2', 2),"
                + " (2, '" + REMOVED + "', '2024-06-01', '2025-01-01', 'PE', 'AST', NULL, NULL, 80, '2019-01-01', NULL, 21, 'h3', 1),"
                + " (2, '" + REMOVED + "', '2025-01-01', '2026-03-01', 'AT', 'AST', NULL, NULL, 85, '2019-01-01', NULL, 21, 'h4', 1),"
                + " (3, '" + NEIGHBOUR + "', '2025-01-01', NULL, 'AT', 'IRU', NULL, NULL, 120, '2018-01-01', NULL, 31, 'h5', 1)");
        jdbc.execute("INSERT INTO gold.car_agent_app_feicao (camada, imovel_id, cod_imovel, cod_tema, num_area, geom, conjunto_hash,"
                + " valido_de, valido_ate, carga_id) VALUES"
                // ACTIVE: an APP set replaced on 2026-02-01 (the closed one must not count), hydrography without num_area
                + " ('APPS', 1, '" + ACTIVE + "', 'APP_TOTAL', 99, " + box(-52.0, -12.0, -51.999, -11.999) + ", 'c0', '2025-01-01', '2026-02-01', 4),"
                + " ('APPS', 1, '" + ACTIVE + "', 'APP_TOTAL', 5, " + box(-52.0, -12.0, -51.999, -11.999) + ", 'c1', '2026-02-01', NULL, 4),"
                + " ('APPS', 1, '" + ACTIVE + "', 'APP_RIO_ATE_10', 5, " + box(-52.0, -12.0, -51.999, -11.999) + ", 'c1', '2026-02-01', NULL, 4),"
                + " ('HIDROGRAFIA', 1, '" + ACTIVE + "', 'RIO_ATE_10', NULL, " + box(-51.999, -11.999, -51.998, -11.998) + ", 'c2',"
                + " '2026-01-10', NULL, 5),"
                // REMOVED: last legal reserve set closed with the removal; an older set; a layer emptied before the last version
                + " ('RESERVA_LEGAL', 2, '" + REMOVED + "', 'ARL_PROPOSTA', 7, " + box(-52.005, -12.0, -52.004, -11.999) + ", 'c3',"
                + " '2024-06-01', '2025-01-01', 1),"
                + " ('RESERVA_LEGAL', 2, '" + REMOVED + "', 'ARL_PROPOSTA', 17, " + box(-52.005, -12.0, -52.003, -11.999) + ", 'c4',"
                + " '2025-01-01', '2026-03-01', 7),"
                + " ('USO_RESTRITO', 2, '" + REMOVED + "', 'USO_RESTRITO', 3, " + box(-52.005, -12.0, -52.004, -11.999) + ", 'c5',"
                + " '2024-06-01', '2025-01-01', 1)");
        jdbc.execute("INSERT INTO gold.car_agent_app_descartado (carga_id, uf, camada, cod_imovel, motivo, detalhe, atributos) VALUES"
                + " (2, 'MT', 'AREA_IMOVEL', '" + ACTIVE + "', 'duplicado', 'kept: AT', '{}'),"
                + " (3, 'MT', 'AREA_IMOVEL', '" + ACTIVE + "', 'duplicado', 'kept: AT', '{}'),"
                + " (4, 'MT', 'APPS', '" + ACTIVE + "', 'geometria_invalida', 'Self-intersection', '{}'),"
                + " (4, 'MT', 'APPS', '" + MISSING + "', 'orfao', 'property not loaded (AREA_IMOVEL of the state)', '{}')");
    }

    /** Runs a Block and checks its outputs the way the executor does: declared, typed, required present. */
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

    static double area(String geometry) {
        return jdbc.queryForObject("SELECT ST_Area((" + geometry + ")::geography) / 1e4", Double.class);
    }

    // ---------------------------------------------------------------- car-property

    @Test
    void propertyReadsTheCurrentVersionAndFlagsTheAreaMismatch() throws Exception {
        Map<String, Object> out = run(new CarPropertyBlock(queries), Map.of("codImovel", ACTIVE));
        assertEquals("2025-06-01", out.get("versionSince"));
        assertEquals("Ativo", out.get("statusLabel"));
        assertEquals("Sorriso", out.get("municipality"));
        assertEquals(false, out.get("removed"));
        assertNull(out.get("removedAt"));
        double calculated = area(box(-52.0, -12.0, -51.99, -11.99));
        assertEquals(calculated, (Double) out.get("calculatedAreaHa"), 0.001);
        assertEquals((200 - calculated) * 100 / calculated, (Double) out.get("areaDifferencePct"), 0.01);
        assertEquals(true, out.get("areaMismatch"));
    }

    @Test
    void removedPropertyReadsItsLastVersion() throws Exception {
        Map<String, Object> out = run(new CarPropertyBlock(queries), Map.of("codImovel", REMOVED));
        assertEquals("2026-03-01", out.get("removedAt"));
        assertEquals(true, out.get("removed"));
        assertEquals("2025-01-01", out.get("versionSince"));
        assertEquals("AT", out.get("status"));
        assertEquals(85.0, out.get("declaredAreaHa"));
        // 85 declared vs ~84 drawn: within 5%
        assertEquals(false, out.get("areaMismatch"));
    }

    @Test
    void unknownPropertyIsNotFound() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new CarPropertyBlock(queries).execute(Map.of("codImovel", MISSING), null));
        assertThrows(IllegalArgumentException.class,
                () -> new CarPropertyGeometryBlock(queries).execute(Map.of("codImovel", MISSING), null));
    }

    // ---------------------------------------------------------------- geometry / report-area

    @Test
    void geometryOfARemovedPropertyIsItsLastOne() throws Exception {
        Map<String, Object> out = run(new CarPropertyGeometryBlock(queries), Map.of("codImovel", REMOVED));
        assertEquals(area(box(-52.005, -12.0, -51.998, -11.99)), (Double) out.get("areaHa"), 0.001);
        Map<String, Object> area = new ReportAreaBlock(queries, new GisAreas(queries)).execute(Map.of("codImovel", REMOVED), null);
        assertEquals(true, area.get("isCar"));
        assertEquals(out.get("areaHa"), area.get("areaHa"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapOfARemovedPropertyDrawsItsLastFeatures() throws Exception {
        Map<String, Object> out = run(new CarPropertyMapBlock(queries), Map.of("codImovel", REMOVED, "width", 400, "height", 300));
        assertTrue(((String) out.get("image")).startsWith("data:image/png;base64,"));
        List<Map<String, Object>> legend = (List<Map<String, Object>>) out.get("legend");
        assertTrue(legend.stream().anyMatch(l -> "RESERVA_LEGAL".equals(l.get("layer"))), legend.toString());
    }

    // ---------------------------------------------------------------- car-environmental-layers

    @Test
    @SuppressWarnings("unchecked")
    void layersUseCurrentFeaturesAndCalculateHydrography() throws Exception {
        Map<String, Object> out = run(new CarEnvironmentalLayersBlock(queries), Map.of("codImovel", ACTIVE));
        List<Map<String, Object>> layers = (List<Map<String, Object>>) out.get("layers");
        assertEquals(List.of("APPS", "HIDROGRAFIA"), layers.stream().map(l -> l.get("layer")).toList());
        assertEquals(5.0, layers.get(0).get("declaredAreaHa"), "aggregate of the current set, not the closed one");
        assertEquals("declared", layers.get(0).get("areaSource"));
        Map<String, Object> hydro = layers.get(1);
        assertEquals("calculated", hydro.get("areaSource"));
        double expected = area(box(-51.999, -11.999, -51.998, -11.998));
        assertTrue(expected > 1);
        assertEquals(expected, (Double) hydro.get("declaredAreaHa"), 0.001);
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(expected, (Double) summary.get("hydrographyHa"), 0.001);
        assertEquals(true, summary.get("hydrographyCalculated"));
        assertEquals(200.0, summary.get("propertyAreaHa"));
        assertEquals(false, out.get("hasLegalReserve"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void layersOfARemovedPropertyAreItsLastFeatures() throws Exception {
        Map<String, Object> out = run(new CarEnvironmentalLayersBlock(queries), Map.of("codImovel", REMOVED));
        List<Map<String, Object>> layers = (List<Map<String, Object>>) out.get("layers");
        assertEquals(List.of("RESERVA_LEGAL"), layers.stream().map(l -> l.get("layer")).toList(),
                "the restricted use layer was already empty when the last version started");
        assertEquals(17.0, layers.get(0).get("declaredAreaHa"));
        assertEquals(true, out.get("hasLegalReserve"));
        assertEquals(85.0, ((Map<String, Object>) out.get("summary")).get("propertyAreaHa"));
    }

    // ---------------------------------------------------------------- car-overlaps

    @Test
    @SuppressWarnings("unchecked")
    void overlapsCompareWithCurrentPropertiesOnly() throws Exception {
        Map<String, Object> out = run(new CarOverlapsBlock(queries), Map.of("codImovel", ACTIVE));
        List<Map<String, Object>> overlaps = (List<Map<String, Object>>) out.get("overlaps");
        assertEquals(List.of(NEIGHBOUR), overlaps.stream().map(o -> o.get("codImovel")).toList(), "the removed one is not a neighbour");
        assertEquals(area(box(-51.995, -12.0, -51.99, -11.99)), (Double) overlaps.get(0).get("overlapHa"), 0.01);

        Map<String, Object> removed = run(new CarOverlapsBlock(queries), Map.of("codImovel", REMOVED));
        List<Map<String, Object>> fromRemoved = (List<Map<String, Object>>) removed.get("overlaps");
        assertEquals(List.of(ACTIVE), fromRemoved.stream().map(o -> o.get("codImovel")).toList());
    }

    // ---------------------------------------------------------------- car-history

    @Test
    @SuppressWarnings("unchecked")
    void historyCarriesTheCalculatedAreaAndItsChange() throws Exception {
        Map<String, Object> out = run(new CarHistoryBlock(queries), Map.of("codImovel", ACTIVE));
        List<Map<String, Object>> versions = (List<Map<String, Object>>) out.get("versions");
        assertEquals(2, out.get("count"));
        assertNull(out.get("removedAt"));
        double before = area(box(-52.0, -12.0, -51.995, -11.995));
        double after = area(box(-52.0, -12.0, -51.99, -11.99));
        assertEquals(before, (Double) versions.get(0).get("calculatedAreaHa"), 0.001);
        assertNull(versions.get(0).get("areaChangeHa"));
        assertEquals(false, versions.get(0).get("geometryChanged"));
        assertEquals(after - before, (Double) versions.get(1).get("areaChangeHa"), 0.001);
        assertEquals((after - before) * 100 / before, (Double) versions.get(1).get("areaChangePct"), 0.01);
        assertEquals(true, versions.get(1).get("geometryChanged"));

        Map<String, Object> removed = run(new CarHistoryBlock(queries), Map.of("codImovel", REMOVED));
        assertEquals("2026-03-01", removed.get("removedAt"));
        List<Map<String, Object>> removedVersions = (List<Map<String, Object>>) removed.get("versions");
        assertEquals(0.0, removedVersions.get(1).get("areaChangeHa"), "same geometry, only attributes changed");
        assertEquals("2026-03-01", removedVersions.get(1).get("validTo"));
    }

    // ---------------------------------------------------------------- car-data-reference

    @Test
    @SuppressWarnings("unchecked")
    void dataReferenceGivesTheLatestCompletedLoadPerLayer() throws Exception {
        Map<String, Object> out = run(new CarDataReferenceBlock(queries), Map.of("codImovel", ACTIVE));
        assertEquals("MT", out.get("uf"));
        List<Map<String, Object>> layers = (List<Map<String, Object>>) out.get("layers");
        assertEquals(9, layers.size());
        assertEquals("AREA_IMOVEL", layers.get(0).get("layer"));
        assertEquals("2026-03-01", layers.get(0).get("publishedAt"));
        assertNotNull(layers.get(0).get("loadedAt"));
        Map<String, Object> hydro = layers.stream().filter(l -> "HIDROGRAFIA".equals(l.get("layer"))).findFirst().orElseThrow();
        assertEquals("2026-01-10", hydro.get("publishedAt"), "the failed load does not count");
        Map<String, Object> fallow = layers.stream().filter(l -> "AREA_POUSIO".equals(l.get("layer"))).findFirst().orElseThrow();
        assertEquals(false, fallow.get("loaded"));
        assertNull(fallow.get("publishedAt"));
        assertEquals("2026-03-01", out.get("latestPublishedAt"));
        assertEquals("2026-01-10", out.get("oldestPublishedAt"));
        assertEquals(true, out.get("hasData"));
        assertEquals(false, out.get("complete"));

        Map<String, Object> none = run(new CarDataReferenceBlock(queries), Map.of("uf", "ba"));
        assertEquals("BA", none.get("uf"));
        assertEquals(false, none.get("hasData"));
        assertNull(none.get("latestPublishedAt"));
        assertThrows(IllegalArgumentException.class,
                () -> new CarDataReferenceBlock(queries).execute(Map.of("uf", "GO", "codImovel", ACTIVE), null));
        assertThrows(IllegalArgumentException.class, () -> new CarDataReferenceBlock(queries).execute(Map.of(), null));
    }

    // ---------------------------------------------------------------- car-quarantine

    @Test
    @SuppressWarnings("unchecked")
    void quarantineListsTheRowsSetAsideForTheProperty() throws Exception {
        Map<String, Object> out = run(new CarQuarantineBlock(queries), Map.of("codImovel", ACTIVE));
        assertEquals(3L, ((Number) out.get("count")).longValue());
        assertEquals(true, out.get("hasItems"));
        assertEquals(false, out.get("truncated"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals("2026-03-01", items.get(0).get("publishedAt"), "most recent publication first");
        assertEquals("duplicado", items.get(0).get("reason"));
        List<Map<String, Object>> byReason = (List<Map<String, Object>>) out.get("byReason");
        assertEquals("duplicado", byReason.get(0).get("reason"));
        assertEquals(2L, ((Number) byReason.get(0).get("count")).longValue());
        assertEquals("2026-03-01", byReason.get(0).get("lastPublishedAt"));

        Map<String, Object> orphan = run(new CarQuarantineBlock(queries), Map.of("codImovel", MISSING));
        assertEquals("orfao", ((List<Map<String, Object>>) orphan.get("items")).get(0).get("reason"));

        Map<String, Object> clean = run(new CarQuarantineBlock(queries), Map.of("codImovel", NEIGHBOUR));
        assertEquals(false, clean.get("hasItems"));
        assertTrue(((List<?>) clean.get("items")).isEmpty());
    }

    // ---------------------------------------------------------------- car-in-area

    static Map<String, Object> polygon(double west, double south, double east, double north) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(List.of(west, south), List.of(east, south),
                List.of(east, north), List.of(west, north), List.of(west, south))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inAreaListsCurrentPropertiesAndTheCoverage() throws Exception {
        Map<String, Object> out = run(new CarInAreaBlock(queries), Map.of("geometry", polygon(-52.002, -12.0, -51.988, -11.99)));
        List<Map<String, Object>> properties = (List<Map<String, Object>>) out.get("properties");
        assertEquals(List.of(ACTIVE, NEIGHBOUR), properties.stream().map(p -> p.get("codImovel")).toList(),
                "largest overlap first; the removed property is not listed");
        assertEquals(2L, ((Number) out.get("count")).longValue());
        double areaHa = area(box(-52.002, -12.0, -51.988, -11.99));
        double activeHa = area(box(-52.0, -12.0, -51.99, -11.99));
        double neighbourPiece = area(box(-51.995, -12.0, -51.988, -11.99));
        double covered = area(box(-52.0, -12.0, -51.988, -11.99));
        assertEquals(areaHa, (Double) out.get("areaHa"), 0.01);
        Map<String, Object> active = properties.get(0);
        assertEquals(activeHa, (Double) active.get("overlapHa"), 0.01);
        assertEquals(100.0, (Double) active.get("overlapPctOfProperty"), 0.01);
        assertEquals(activeHa * 100 / areaHa, (Double) active.get("overlapPctOfArea"), 0.01);
        assertEquals("Sorriso", active.get("municipality"));
        assertEquals(200.0, active.get("declaredAreaHa"));
        assertEquals(activeHa + neighbourPiece, (Double) out.get("totalOverlapHa"), 0.01);
        assertEquals(covered, (Double) out.get("coveredHa"), 0.01);
        assertEquals(covered * 100 / areaHa, (Double) out.get("coveragePct"), 0.01);
        assertEquals(false, out.get("truncated"));

        Map<String, Object> limited = run(new CarInAreaBlock(queries),
                Map.of("geometry", polygon(-52.002, -12.0, -51.988, -11.99), "limit", 1));
        assertEquals(1, ((List<?>) limited.get("properties")).size());
        assertEquals(true, limited.get("truncated"));
        assertEquals(covered, (Double) limited.get("coveredHa"), 0.01, "the coverage does not depend on the limit");

        Map<String, Object> empty = run(new CarInAreaBlock(queries), Map.of("geometry", polygon(-50.0, -10.0, -49.99, -9.99)));
        assertEquals(false, empty.get("hasProperties"));
        assertEquals(0.0, empty.get("coveragePct"));

        assertThrows(IllegalArgumentException.class,
                () -> new CarInAreaBlock(queries).execute(Map.of("geometry", polygon(500000, 8000000, 500100, 8000100)), null));
    }
}
