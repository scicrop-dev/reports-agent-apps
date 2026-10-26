package com.infinitestack.reports.blocks.car;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.DataRequirement;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-property-map}: a PNG map of a CAR property with its current feature layers and the
 * neighbouring properties (BLOCKS.md section 2). The image is a {@code data:} URL so a template
 * can embed it directly and the PDF stays self-contained (no tiles, no network).
 *
 * <p>Geometries come from the database already simplified to the image resolution, so a property
 * with thousands of vertices costs the same as a small one. Drawing is in {@link PropertyMapRenderer}.
 */
@Component
public class CarPropertyMapBlock implements ReportBlock {

    static final int DEFAULT_WIDTH = 1000;
    static final int DEFAULT_HEIGHT = 700;
    static final int MIN_SIZE = 300;
    static final int MAX_SIZE = 2000;
    /** Neighbours drawn at most, nearest first. */
    static final int MAX_NEIGHBOURS = 300;

    private static final String SCHEMA = "gold";
    private static final String VERSION_TABLE = "car_agent_app_imovel_versao";
    private static final String GEOMETRY_TABLE = "car_agent_app_imovel_geometria";
    private static final String FEATURE_TABLE = "car_agent_app_feicao";
    private static final String LAYER_TABLE = "car_agent_app_camada";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String PROPERTY_SQL = """
            SELECT v.imovel_id, g.id AS geometria_id,
                   ST_XMin(g.geom) AS min_lon, ST_YMin(g.geom) AS min_lat,
                   ST_XMax(g.geom) AS max_lon, ST_YMax(g.geom) AS max_lat
              FROM gold.car_agent_app_imovel_versao v
              JOIN gold.car_agent_app_imovel_geometria g ON g.id = v.geometria_id
             WHERE v.cod_imovel = ? AND v.valido_ate IS NULL
            """;

    private static final String PROPERTY_GEOMETRY_SQL = """
            SELECT ST_AsGeoJSON(ST_SimplifyPreserveTopology(g.geom, ?), 6)
              FROM gold.car_agent_app_imovel_geometria g
             WHERE g.id = ?
            """;

    private static final String FEATURES_SQL = """
            SELECT f.camada, c.nome, ST_AsGeoJSON(ST_SimplifyPreserveTopology(f.geom, ?), 6) AS geojson
              FROM gold.car_agent_app_feicao f
              JOIN gold.car_agent_app_camada c ON c.codigo = f.camada
             WHERE f.cod_imovel = ? AND f.valido_ate IS NULL AND f.camada <> 'AREA_IMOVEL'
             ORDER BY c.ordem, f.id
            """;

    /*
     * Current geometries of other properties touching the visible window: && uses the GiST index,
     * the join goes through the partial index of current versions (imovel_id WHERE valido_ate IS NULL).
     * Big neighbours are clipped to a box a bit larger than the image, so their cut edges stay off it.
     */
    private static final String NEIGHBOURS_SQL = """
            SELECT ST_AsGeoJSON(ST_SimplifyPreserveTopology(
                       ST_ClipByBox2D(g.geom, ST_MakeEnvelope(?, ?, ?, ?, 4674)), ?), 6)
              FROM gold.car_agent_app_imovel_geometria g
              JOIN gold.car_agent_app_imovel_versao v
                ON v.imovel_id = g.imovel_id AND v.geometria_id = g.id AND v.valido_ate IS NULL
             WHERE g.geom && ST_MakeEnvelope(?, ?, ?, ?, 4674) AND v.imovel_id <> ?
             ORDER BY g.geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4674)
             LIMIT ?
            """;

    private final BlockQueries queries;
    private final PropertyMapRenderer renderer = new PropertyMapRenderer();

    public CarPropertyMapBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-property-map", "CAR property map",
                "PNG map of the CAR property with its current declared layers and neighbouring properties",
                "1.0.0",
                List.of(BlockPort.required("codImovel", DataType.STRING, "SICAR property code (UF-IBGE-hash)"),
                        BlockPort.optional("width", DataType.NUMBER, "image width in pixels, default 1000 (300-2000)"),
                        BlockPort.optional("height", DataType.NUMBER, "image height in pixels, default 700 (300-2000)")),
                List.of(BlockPort.required("image", DataType.STRING, "PNG as a data:image/png;base64 URL"),
                        BlockPort.required("legend", DataType.ARRAY, "{layer, layerName, color} of the layers drawn")),
                List.of(DataRequirement.table(SCHEMA, VERSION_TABLE, "imovel_id", "cod_imovel", "valido_ate", "geometria_id"),
                        DataRequirement.table(SCHEMA, GEOMETRY_TABLE, "id", "imovel_id", "geom"),
                        DataRequirement.table(SCHEMA, FEATURE_TABLE, "id", "camada", "cod_imovel", "geom", "valido_ate"),
                        DataRequirement.table(SCHEMA, LAYER_TABLE, "codigo", "nome", "ordem")),
                TIMEOUT);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Draws a map of the property for the PDF: its boundary, the declared layers in colour and the "
                        + "neighbouring properties, with north arrow, scale bar and corner coordinates.",
                List.of(
                        "Reads the property geometry, its current features and the neighbours inside the map frame.",
                        "Simplifies the geometries to the image resolution and draws them with Java2D.",
                        "Returns a PNG as a data: URL, so the PDF is self-contained (no tiles, no network)."),
                List.of(
                        "codImovel: the same report input as car-property.",
                        "width / height: fixed values to change the image size (default 1000 x 700 px).",
                        "image goes into <img src=\"{{<node>.image}}\">; legend lists the colours used."),
                List.of(
                        "No satellite background: only the data in the database.",
                        "If the host JRE has no fonts, the map comes without labels."),
                "SICAR, gold.car_agent_app_imovel_geometria and _feicao",
                "<img src=\"{{<node>.image}}\">");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = String.valueOf(inputs.get("codImovel")).trim();
        int width = size(inputs.get("width"), DEFAULT_WIDTH);
        int height = size(inputs.get("height"), DEFAULT_HEIGHT);

        Loaded loaded = queries.read(TIMEOUT, jdbc -> load(jdbc, codImovel, width, height));
        PropertyMapRenderer.Result result = renderer.render(loaded.view(), loaded.data());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(result.png()));
        out.put("legend", PropertyMapRenderer.legendJson(result.legend()));
        return out;
    }

    private record Loaded(PropertyMapRenderer.Viewport view, PropertyMapRenderer.MapData data) {}

    private Loaded load(JdbcTemplate jdbc, String codImovel, int width, int height) {
        List<Map<String, Object>> rows = jdbc.queryForList(PROPERTY_SQL, codImovel);
        if (rows.isEmpty()) throw new IllegalArgumentException("CAR property " + codImovel + " not found");
        Map<String, Object> row = rows.get(0);
        long imovelId = ((Number) row.get("imovel_id")).longValue();
        long geometryId = ((Number) row.get("geometria_id")).longValue();
        PropertyMapRenderer.Viewport view = PropertyMapRenderer.Viewport.fit(
                number(row, "min_lon"), number(row, "min_lat"), number(row, "max_lon"), number(row, "max_lat"),
                width, height);
        double tolerance = view.degreesPerPixel();

        String property = jdbc.queryForObject(PROPERTY_GEOMETRY_SQL, String.class, tolerance, geometryId);

        Map<String, List<String>> byLayer = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query(FEATURES_SQL, rs -> {
            String layer = rs.getString("camada");
            names.putIfAbsent(layer, rs.getString("nome"));
            byLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(rs.getString("geojson"));
        }, tolerance, codImovel);
        List<PropertyMapRenderer.Layer> layers = new ArrayList<>();
        byLayer.forEach((layer, geometries) -> layers.add(new PropertyMapRenderer.Layer(layer, names.get(layer), geometries)));

        // Clip box: the visible window plus a margin, so clipped edges fall outside the image.
        double marginLon = (view.maxLon() - view.minLon()) * 0.05;
        double marginLat = (view.maxLat() - view.minLat()) * 0.05;
        List<String> neighbours = jdbc.queryForList(NEIGHBOURS_SQL, String.class,
                view.minLon() - marginLon, view.minLat() - marginLat, view.maxLon() + marginLon, view.maxLat() + marginLat,
                tolerance,
                view.minLon(), view.minLat(), view.maxLon(), view.maxLat(),
                imovelId, view.centerLon(), view.centerLat(), MAX_NEIGHBOURS);

        return new Loaded(view, new PropertyMapRenderer.MapData(property, layers, neighbours));
    }

    private static double number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    /** A requested size, clamped to {@link #MIN_SIZE}..{@link #MAX_SIZE}; absent means the default. */
    static int size(Object value, int fallback) {
        if (!(value instanceof Number n)) return fallback;
        return (int) Math.max(MIN_SIZE, Math.min(MAX_SIZE, Math.round(n.doubleValue())));
    }
}
