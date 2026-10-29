package com.infinitestack.reports.blocks.car;

import static com.infinitestack.reports.blocks.car.CarTables.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code car-property}: the registration data of a CAR property, from its current version
 * ({@code valido_ate IS NULL}) or, for a property removed from the SICAR, its last version
 * ({@link CarTables#VERSION_IN_EFFECT}), with the labels of status, type and condition
 * (BLOCKS.md section 2). It also compares the declared area with the area calculated from the
 * version's geometry and flags a difference above {@link #AREA_MISMATCH_PCT}.
 */
@Component
public class CarPropertyBlock implements ReportBlock {

    /** Above this difference between declared and calculated area, in percent, {@code areaMismatch} is true. */
    static final double AREA_MISMATCH_PCT = 5.0;

    static final String SQL = "SELECT i.cod_imovel, i.uf, i.cod_municipio, i.removido_em, m.nome AS municipio,"
            + " v.ind_status, s.descricao AS status_label, v.ind_tipo, t.descricao AS tipo_label,"
            + " c.descricao AS condicao, v.mod_fiscal, v.num_area, v.dat_criacao, v.dat_atualizacao, v.valido_de,"
            + " COALESCE(g.area_calc_ha, (ST_Area(g.geom::geography) / 1e4)::numeric) AS area_calc_ha"
            + " FROM " + Q_IMOVEL + " i"
            + VERSION_IN_EFFECT
            + " LEFT JOIN " + Q_IMOVEL_GEOMETRIA + " g ON g.id = v.geometria_id"
            + " LEFT JOIN " + Q_MUNICIPIO + " m ON m.cod_ibge = i.cod_municipio"
            + " LEFT JOIN " + Q_STATUS_CADASTRO + " s ON s.codigo = v.ind_status"
            + " LEFT JOIN " + Q_TIPO_IMOVEL + " t ON t.codigo = v.ind_tipo"
            + " LEFT JOIN " + Q_CONDICAO + " c ON c.id = v.condicao_id"
            + " WHERE i.cod_imovel = ?";

    private final BlockQueries queries;

    public CarPropertyBlock(BlockQueries queries) {
        this.queries = queries;
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("car-property", "CAR property",
                "Registration data of a CAR property (current SICAR version, or the last one if removed)", "1.1.0",
                List.of(CarValues.COD_IMOVEL_INPUT),
                List.of(
                        BlockPort.required("codImovel", DataType.STRING, "SICAR property code"),
                        BlockPort.required("uf", DataType.STRING, "state (UF)"),
                        BlockPort.optional("municipality", DataType.STRING, "municipality name"),
                        BlockPort.required("municipalityIbge", DataType.NUMBER, "IBGE municipality code"),
                        BlockPort.required("status", DataType.STRING, "registration status code (AT, PE, SU, CA)"),
                        BlockPort.optional("statusLabel", DataType.STRING, "registration status label"),
                        BlockPort.required("type", DataType.STRING, "property type code (IRU, AST, PCT)"),
                        BlockPort.optional("typeLabel", DataType.STRING, "property type label"),
                        BlockPort.optional("condition", DataType.STRING, "analysis condition"),
                        BlockPort.optional("fiscalModules", DataType.NUMBER, "fiscal modules"),
                        BlockPort.optional("declaredAreaHa", DataType.NUMBER, "declared area, hectares"),
                        BlockPort.optional("registeredAt", DataType.DATE, "registration date"),
                        BlockPort.optional("updatedAt", DataType.DATE, "last update in SICAR"),
                        BlockPort.required("versionSince", DataType.DATE, "date the current version became valid"),
                        BlockPort.optional("removedAt", DataType.DATE, "date the property left SICAR, null if still there"),
                        BlockPort.required("removed", DataType.BOOLEAN, "the property left the SICAR (data is its last version)"),
                        BlockPort.optional("calculatedAreaHa", DataType.NUMBER,
                                "area calculated from the version's geometry (geodesic), hectares"),
                        BlockPort.optional("areaDifferencePct", DataType.NUMBER,
                                "(declared - calculated) / calculated x 100, 2 decimals; null when either is missing"),
                        BlockPort.required("areaMismatch", DataType.BOOLEAN,
                                "declared and calculated area differ by more than 5%")),
                List.of(
                        requirement(IMOVEL, "id", "cod_imovel", "uf", "cod_municipio", "removido_em"),
                        requirement(IMOVEL_VERSAO, "imovel_id", "valido_ate", "valido_de", "ind_status", "ind_tipo",
                                "condicao_id", "mod_fiscal", "num_area", "dat_criacao", "dat_atualizacao", "geometria_id"),
                        requirement(IMOVEL_GEOMETRIA, "id", "geom", "area_calc_ha"),
                        requirement(MUNICIPIO, "cod_ibge", "nome"),
                        requirement(STATUS_CADASTRO, "codigo", "descricao"),
                        requirement(TIPO_IMOVEL, "codigo", "descricao"),
                        requirement(CONDICAO, "id", "descricao")),
                null);
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("CAR",
                "Identifies a rural property registered in the CAR (Cadastro Ambiental Rural): where it is, "
                        + "what kind of property it is, its status in the SICAR and its declared area.",
                List.of(
                        "Finds the property by its SICAR code (UF-<IBGE>-<32 hex>).",
                        "Reads the current version of the registration (the one with no end date) and resolves the "
                                + "codes into labels: status, property type, condition and municipality.",
                        "removedAt is filled when the property left the SICAR; the data is then the last known version "
                                + "(the one closed on that date) and removed is true.",
                        "Compares the declared area (num_area) with the area calculated from the geometry of the same "
                                + "version: areaDifferencePct = (declared - calculated) / calculated x 100, and areaMismatch "
                                + "is true when its absolute value is above 5%."),
                List.of(
                        "codImovel: connect a report input of type string (the reader types the code).",
                        "municipalityIbge feeds per-municipality Blocks such as brgeo-crop-production.",
                        "Usually the first node of a CAR report: every other CAR Block takes the same codImovel.",
                        "removed and areaMismatch are booleans for template warnings: {{#<node>.removed}}...{{/<node>.removed}}."),
                List.of(
                        "Everything here is self-declared by the owner and may not have been analysed by the environmental agency.",
                        "Fails with 'CAR property ... not found' when the code is not in the base.",
                        "The 5% threshold is a warning, not a legal criterion: a declared area rounded by the owner or a "
                                + "drawing in another projection can explain small differences."),
                "SICAR, collected by car-agent-app into gold.car_agent_app_imovel and _imovel_versao",
                "{{<node>.codImovel}} - {{<node>.municipality}}/{{<node>.uf}} - {{<node>.statusLabel}}");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) {
        String codImovel = CarValues.codImovel(inputs);
        List<Map<String, Object>> rows = queries.read(CarValues.QUERY_TIMEOUT, jdbc -> jdbc.query(SQL, (rs, n) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("codImovel", rs.getString("cod_imovel"));
            out.put("uf", rs.getString("uf"));
            out.put("municipality", rs.getString("municipio"));
            out.put("municipalityIbge", rs.getInt("cod_municipio"));
            out.put("status", rs.getString("ind_status"));
            out.put("statusLabel", rs.getString("status_label"));
            out.put("type", rs.getString("ind_tipo"));
            out.put("typeLabel", rs.getString("tipo_label"));
            out.put("condition", rs.getString("condicao"));
            out.put("fiscalModules", CarValues.hectares(rs.getBigDecimal("mod_fiscal")));
            out.put("declaredAreaHa", CarValues.hectares(rs.getBigDecimal("num_area")));
            out.put("registeredAt", CarValues.isoDate(rs, "dat_criacao"));
            out.put("updatedAt", CarValues.isoDate(rs, "dat_atualizacao"));
            out.put("versionSince", CarValues.isoDate(rs, "valido_de"));
            out.put("removedAt", CarValues.isoDate(rs, "removido_em"));
            out.put("removed", rs.getObject("removido_em") != null);
            Double declared = CarValues.hectares(rs.getBigDecimal("num_area"));
            Double calculated = CarValues.hectares(rs.getBigDecimal("area_calc_ha"));
            Double difference = areaDifferencePct(declared, calculated);
            out.put("calculatedAreaHa", calculated);
            out.put("areaDifferencePct", difference);
            out.put("areaMismatch", areaMismatch(difference));
            return out;
        }, codImovel));
        if (rows.isEmpty()) throw CarValues.notFound(codImovel);
        return rows.get(0);
    }

    /** (declared - calculated) / calculated x 100 with 2 decimals; null when either is missing or calculated is not positive. */
    static Double areaDifferencePct(Double declaredHa, Double calculatedHa) {
        if (declaredHa == null || calculatedHa == null || calculatedHa <= 0) return null;
        return CarValues.round(java.math.BigDecimal.valueOf((declaredHa - calculatedHa) * 100.0 / calculatedHa), 2);
    }

    static boolean areaMismatch(Double differencePct) {
        return differencePct != null && Math.abs(differencePct) > AREA_MISMATCH_PCT;
    }
}
