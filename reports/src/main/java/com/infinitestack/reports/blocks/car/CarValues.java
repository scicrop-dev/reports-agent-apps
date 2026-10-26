package com.infinitestack.reports.blocks.car;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockPort;

/**
 * Small value conventions shared by the CAR Blocks, so every Block rounds, dates and fails the
 * same way: hectares with 4 decimals, percentages with 2, dates as ISO strings (what
 * {@link DataType#DATE} expects) and one message for an unknown property.
 */
final class CarValues {

    private CarValues() {}

    /** Statement timeout of the per-property queries; below the default Block timeout (30 s). */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(20);

    static final BlockPort COD_IMOVEL_INPUT = BlockPort.required("codImovel", DataType.STRING,
            "SICAR property code (UF-<IBGE>-<32 hex>)");

    static String codImovel(Map<String, Object> inputs) {
        return String.valueOf(inputs.get("codImovel")).trim();
    }

    static IllegalArgumentException notFound(String codImovel) {
        return new IllegalArgumentException("CAR property " + codImovel + " not found");
    }

    static Double hectares(BigDecimal value) {
        return round(value, 4);
    }

    static Double hectares(double value) {
        return round(BigDecimal.valueOf(value), 4);
    }

    /** {@code part} as a percentage of {@code whole}, 2 decimals; 0 when the whole is unknown or zero. */
    static double percent(double part, Double whole) {
        if (whole == null || whole <= 0) return 0.0;
        return round(BigDecimal.valueOf(part * 100.0 / whole), 2);
    }

    static Double round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** A date column as an ISO string (yyyy-MM-dd), or null. */
    static String isoDate(ResultSet rs, String column) throws SQLException {
        LocalDate date = rs.getObject(column, LocalDate.class);
        return date == null ? null : date.toString();
    }
}
