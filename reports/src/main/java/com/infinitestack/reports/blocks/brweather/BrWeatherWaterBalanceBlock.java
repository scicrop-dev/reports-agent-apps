package com.infinitestack.reports.blocks.brweather;

import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.CHIRPS_DAY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.POWER_CELL;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.POWER_DAILY;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.requirement;
import static com.infinitestack.reports.blocks.brweather.BrWeatherTables.round;

import java.awt.Color;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.infinitestack.reports.blocks.sathub.SatelliteHubLatestImageBlock;
import com.infinitestack.reports.blocks.sathub.imagery.CogReader;
import com.infinitestack.reports.blocks.sathub.imagery.HttpCogReader;
import com.infinitestack.reports.blocks.sathub.imagery.LocalFileRangeFetcher;
import com.infinitestack.reports.engine.DataType;
import com.infinitestack.reports.engine.block.BlockDefinition;
import com.infinitestack.reports.engine.block.BlockDocs;
import com.infinitestack.reports.engine.block.BlockPort;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.infinitestack.reports.engine.block.ExecutionMetadata;
import com.infinitestack.reports.engine.block.ReportBlock;

/**
 * {@code brweather-water-balance}: a daily sequential soil water balance of an area (Thornthwaite and
 * Mather, 1955, in the daily form of Pereira, Angelocci and Sentelhas), with the rain of CHIRPS and
 * the reference evapotranspiration of NASA POWER (FAO-56 Penman-Monteith).
 *
 * <p>The soil starts full ({@code storage = cadMm} on the day before {@code from}), so the first days
 * of a dry period show less deficit than they would after a dry spell: start the period some weeks
 * before the dates that matter, or at the end of the rainy season, when the soil is full.
 */
@Component
public class BrWeatherWaterBalanceBlock implements ReportBlock {

    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 366;
    static final double DEFAULT_CAD_MM = 50;
    static final double MAX_CAD_MM = 500;
    static final double DEFAULT_KC = 1.0;
    static final double MAX_KC = 3.0;
    /** A day counts as a deficit day when the crop got at least this much less water than it demanded. */
    static final double DEFICIT_DAY_MM = 1.0;
    static final int CHART_WIDTH = 900;
    static final int CHART_HEIGHT = 300;
    static final Color STORAGE = new Color(0x2E7D32);
    static final Color DEFICIT = new Color(0xC62828);
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(25);

    /** One day of input: rain (null when missing) and ET0 (never null here). */
    record Input(LocalDate date, Double rainMm, double et0Mm) {}

    /** One day of the balance, mm. */
    record Day(LocalDate date, double etcMm, double etaMm, double storageMm, double deficitMm, double excessMm) {}

    private final BlockQueries queries;
    private final ChirpsClips clips;
    private final DailyBarChart chart = new DailyBarChart();

    @Autowired
    public BrWeatherWaterBalanceBlock(BlockQueries queries,
                                      @Value("${reports.brweather.chirps-dir:/opt/infinitestack/var/lake/bronze/br-weather-agent-app/chirps}") String root) {
        this(queries, new HttpCogReader(new LocalFileRangeFetcher()), Path.of(root));
    }

    BrWeatherWaterBalanceBlock(BlockQueries queries, CogReader reader, Path root) {
        this.queries = queries;
        this.clips = new ChirpsClips(queries, reader, root);
    }

    @Override
    public BlockDefinition definition() {
        return new BlockDefinition("brweather-water-balance", "Soil water balance (CHIRPS + NASA POWER)",
                "Daily sequential soil water balance of an area (Thornthwaite-Mather): rain from CHIRPS, ET0 from NASA POWER,"
                        + " storage, deficit and excess, with a chart",
                "1.0.0",
                List.of(
                        BlockPort.required("geometry", DataType.GEOMETRY,
                                "area, GeoJSON in SIRGAS 2000 (lon/lat); connect car-property-geometry.geometry"),
                        BlockPort.optional("from", DataType.DATE, "first day; default 'days' days before 'to'"),
                        BlockPort.optional("to", DataType.DATE, "last day; default the latest day with both rain and weather loaded"),
                        BlockPort.optional("days", DataType.NUMBER, "length of the period when 'from' is empty; default 90, at most 366"),
                        BlockPort.optional("cadMm", DataType.NUMBER, "available water capacity of the root zone (CAD), mm; default 50"),
                        BlockPort.optional("kc", DataType.NUMBER, "crop coefficient applied to ET0 (ETc = kc x ET0); default 1.0")),
                List.of(
                        BlockPort.required("found", DataType.BOOLEAN, "the balance could be computed"),
                        BlockPort.optional("message", DataType.STRING, "why it could not"),
                        BlockPort.optional("from", DataType.DATE, "first day of the period"),
                        BlockPort.optional("to", DataType.DATE, "last day of the period"),
                        BlockPort.optional("latestRainDay", DataType.DATE, "latest CHIRPS day loaded"),
                        BlockPort.optional("latestWeatherDay", DataType.DATE, "latest NASA POWER day loaded for the area"),
                        BlockPort.required("cadMm", DataType.NUMBER, "CAD used, mm"),
                        BlockPort.required("kc", DataType.NUMBER, "crop coefficient used"),
                        BlockPort.required("days", DataType.ARRAY,
                                "every day of the period, oldest first: {date, rainMm, et0Mm, etcMm, etaMm, storageMm, deficitMm, excessMm,"
                                        + " rainMissing, et0Estimated} (mm; rainMm null when the day has no CHIRPS value)"),
                        BlockPort.optional("totalRainMm", DataType.NUMBER, "rain of the period (days with data), mm"),
                        BlockPort.optional("totalEt0Mm", DataType.NUMBER, "ET0 of the period, mm"),
                        BlockPort.optional("totalEtcMm", DataType.NUMBER, "crop demand of the period (kc x ET0), mm"),
                        BlockPort.optional("totalEtaMm", DataType.NUMBER, "actual evapotranspiration of the period, mm"),
                        BlockPort.optional("totalDeficitMm", DataType.NUMBER, "water deficit of the period (ETc - ETa), mm"),
                        BlockPort.optional("totalExcessMm", DataType.NUMBER, "water excess of the period (runoff and drainage), mm"),
                        BlockPort.optional("finalStorageMm", DataType.NUMBER, "soil water on the last day, mm"),
                        BlockPort.optional("finalStoragePercent", DataType.NUMBER, "soil water on the last day, % of CAD"),
                        BlockPort.required("deficitDays", DataType.NUMBER, "days with a deficit of 1 mm or more"),
                        BlockPort.required("maxConsecutiveDeficitDays", DataType.NUMBER, "longest run of consecutive deficit days"),
                        BlockPort.required("missingRainDays", DataType.NUMBER, "days without CHIRPS value, counted as no rain"),
                        BlockPort.required("estimatedEt0Days", DataType.NUMBER,
                                "days whose ET0 was estimated (Hargreaves, or the period mean) because an input of Penman-Monteith was missing"),
                        BlockPort.required("cells", DataType.ARRAY, "NASA POWER cells used: {lat, lon, elevationM, weightPercent}"),
                        BlockPort.required("pixels", DataType.NUMBER, "CHIRPS pixels averaged"),
                        BlockPort.required("chart", DataType.STRING,
                                "PNG chart (900 x 300): daily rain as bars, soil water storage (green) and daily deficit (red) as lines,"
                                        + " as a data:image/png;base64 URL")),
                List.of(
                        requirement(POWER_CELL, "lat", "lon", "elevation_m", "geom", "valid_to"),
                        requirement(POWER_DAILY, "lat", "lon", "day", "t2m", "t2m_max", "t2m_min", "rh2m", "ws2m",
                                "allsky_sfc_sw_dwn", "prectotcorr", "valid_to"),
                        requirement(CHIRPS_DAY, ChirpsClips.COLUMNS)),
                Duration.ofSeconds(90));
    }

    @Override
    public BlockDocs docs() {
        return new BlockDocs("BR Weather",
                "Follows the water in the soil of an area day by day: how much rain came in (CHIRPS), how much the crop "
                        + "demanded (ET0 of NASA POWER times kc), what was left in the soil, and the days of deficit and excess.",
                List.of(
                        "Takes the period from 'from' to 'to', or the last 'days' days up to the latest day with both CHIRPS and NASA POWER.",
                        "Reads the daily rain of the area from the CHIRPS clips (as brweather-rainfall) and the daily ET0 from "
                                + "NASA POWER (FAO-56 Penman-Monteith, as brweather-daily-weather).",
                        "Runs a sequential Thornthwaite-Mather balance: the soil starts full (storage = cadMm); a dry day takes water "
                                + "from the soil exponentially (storage = cadMm x exp(accumulated loss / cadMm)); a wet day refills it up to cadMm "
                                + "and the rest is excess.",
                        "Actual evapotranspiration is the rain plus what the soil gave; the deficit is ETc minus it."),
                List.of(
                        "geometry: connect car-property-geometry.geometry.",
                        "cadMm: about 1 mm per cm of root depth in a sandy soil, 1.5 to 2 in a clay soil (e.g. 40 to 80 mm for "
                                + "soybean); kc: the crop coefficient of its stage (FAO-56 table 12), 1.0 for the reference grass.",
                        "chart goes straight into the template: <img src=\"{{<node>.chart}}\">."),
                List.of(
                        "The soil starts full on the day before 'from': start the period some weeks before the dates that matter, "
                                + "or at the end of the rainy season.",
                        "One CAD and one kc for the whole period: an indicator of water stress, not an irrigation schedule.",
                        "A day without CHIRPS value counts as no rain (rainMissing, missingRainDays), which overstates the deficit.",
                        "A day without the radiation (usually the last 1 or 2 days) or other input of Penman-Monteith takes the "
                                + "Hargreaves ET0 (FAO-56 eq. 52, from the temperatures); without temperatures, the mean ET0 of the period "
                                + "(et0Estimated, estimatedEt0Days).",
                        "Rain is about 5.5 km and ET0 about 55 x 65 km: both describe the region of the area."),
                "CHIRPS v3 daily (Climate Hazards Center, UCSB) and NASA POWER (NASA Langley), loaded by BR Weather",
                "{{#<node>.found}}<img src=\"{{<node>.chart}}\"> De {{<node>.from}} a {{<node>.to}}: chuva {{<node>.totalRainMm}} mm,"
                        + " ETc {{<node>.totalEtcMm}} mm, déficit {{<node>.totalDeficitMm}} mm em {{<node>.deficitDays}} dias"
                        + "{{/<node>.found}}{{^<node>.found}}{{<node>.message}}{{/<node>.found}}");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> inputs, ExecutionMetadata metadata) throws Exception {
        if (!(inputs.get("geometry") instanceof Map<?, ?> geometry)) throw new IllegalArgumentException("geometry is required");
        List<List<double[][]>> polygons = SatelliteHubLatestImageBlock.polygons((Map<String, Object>) geometry);
        String geoJson = BrWeatherTables.geoJson(geometry);
        LocalDate from = BrWeatherDailyBlock.date(inputs.get("from"));
        LocalDate to = BrWeatherDailyBlock.date(inputs.get("to"));
        int days = inputs.get("days") instanceof Number n ? Math.max(1, Math.min(MAX_DAYS, n.intValue())) : DEFAULT_DAYS;
        double cad = number(inputs.get("cadMm"), DEFAULT_CAD_MM);
        double kc = number(inputs.get("kc"), DEFAULT_KC);
        if (!(cad > 0 && cad <= MAX_CAD_MM)) throw new IllegalArgumentException("cadMm must be above 0 and at most " + MAX_CAD_MM);
        if (!(kc > 0 && kc <= MAX_KC)) throw new IllegalArgumentException("kc must be above 0 and at most " + MAX_KC);

        Map<String, Object> out = new HashMap<>();
        out.put("cadMm", cad);
        out.put("kc", kc);
        PowerArea area = queries.read(QUERY_TIMEOUT, jdbc -> PowerArea.find(jdbc, geoJson));
        if (area == null) return notFound(out, "the area is outside the NASA POWER cells BR Weather loaded (Brazil)");
        out.put("cells", area.cells);
        LocalDate latestWeather = queries.read(QUERY_TIMEOUT, area::latest);
        LocalDate latestRain = clips.latest();
        out.put("latestWeatherDay", latestWeather == null ? null : latestWeather.toString());
        out.put("latestRainDay", latestRain == null ? null : latestRain.toString());
        if (latestWeather == null) return notFound(out, "BR Weather has no NASA POWER day for the area yet");
        if (latestRain == null) return notFound(out, "BR Weather has no CHIRPS day loaded yet");

        LocalDate end = to != null ? to : latestRain.isBefore(latestWeather) ? latestRain : latestWeather;
        LocalDate start = from != null ? from : end.minusDays(days - 1L);
        if (start.isAfter(end)) throw new IllegalArgumentException("from (" + start + ") is after to (" + end + ")");
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw new IllegalArgumentException("the period is longer than " + MAX_DAYS + " days");
        out.put("from", start.toString());
        out.put("to", end.toString());

        List<Map<String, Object>> weather = queries.read(QUERY_TIMEOUT, jdbc -> area.daily(jdbc, start, end));
        ChirpsClips.Period rain = clips.read(polygons, start, end);
        out.put("pixels", rain.pixels());
        if (rain.series().isEmpty()) {
            return notFound(out, rain.catalogDays() == 0
                    ? "no CHIRPS day loaded between " + start + " and " + end + " (latest: " + latestRain + ")"
                    : "the area is outside the CHIRPS clips or their files are missing in " + clips.root());
        }

        // ET0 of each day: Penman-Monteith, else Hargreaves, else (no temperature) the mean of the period
        Map<LocalDate, Double> pm = new HashMap<>();
        Map<LocalDate, Double> hs = new HashMap<>();
        for (Map<String, Object> day : weather) {
            LocalDate date = LocalDate.parse((String) day.get("date"));
            Double et0 = area.penmanMonteith(day);
            if (et0 != null) pm.put(date, et0);
            Double estimate = area.hargreaves(day);
            if (estimate != null) hs.put(date, estimate);
        }
        if (pm.isEmpty() && hs.isEmpty()) {
            return notFound(out, "no NASA POWER temperature for the area between " + start + " and " + end
                    + " (latest: " + latestWeather + ")");
        }
        double known = 0;
        int knownDays = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Double value = pm.containsKey(d) ? pm.get(d) : hs.get(d);
            if (value != null) {
                known += value;
                knownDays++;
            }
        }
        double fill = known / knownDays;

        Map<LocalDate, Double> rainByDay = new HashMap<>();
        for (Map<String, Object> day : rain.series()) rainByDay.put(LocalDate.parse((String) day.get("date")), (Double) day.get("rainMm"));
        List<Input> series = new ArrayList<>();
        List<Boolean> estimated = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Double et0 = pm.get(d);
            estimated.add(et0 == null);
            if (et0 == null) et0 = hs.getOrDefault(d, fill);
            series.add(new Input(d, rainByDay.get(d), et0));
        }

        List<Day> balance = balance(series, cad, kc);
        out.putAll(summary(series, estimated, balance, cad));
        out.put("found", true);
        out.put("message", null);
        out.put("chart", chart(series, balance));
        return ordered(out);
    }

    /**
     * The sequential daily balance. Storage starts at {@code cad} (full soil). For each day, with
     * P the rain (0 when missing) and ETc = kc x ET0: when P - ETc is negative, the accumulated loss
     * grows by it and storage = cad x exp(loss / cad); otherwise storage grows by
     * P - ETc up to cad and the loss is recomputed as cad x ln(storage / cad). ETa = ETc on a wet day,
     * P + the water the soil gave on a dry day; deficit = ETc - ETa; excess = what does not fit in the
     * soil.
     */
    static List<Day> balance(List<Input> series, double cad, double kc) {
        List<Day> out = new ArrayList<>();
        double storage = cad;
        double loss = 0;
        for (Input day : series) {
            double p = day.rainMm() == null ? 0 : day.rainMm();
            double etc = kc * day.et0Mm();
            double net = p - etc;
            double previous = storage;
            if (net < 0) {
                loss += net;
                storage = cad * Math.exp(loss / cad);
            } else {
                storage = Math.min(cad, storage + net);
                loss = cad * Math.log(storage / cad);
            }
            double change = storage - previous;
            double eta = net < 0 ? p - change : etc;
            double excess = net >= 0 ? net - change : 0;
            out.add(new Day(day.date(), etc, eta, storage, Math.max(0, etc - eta), Math.max(0, excess)));
        }
        return out;
    }

    static Map<String, Object> summary(List<Input> series, List<Boolean> estimated, List<Day> balance, double cad) {
        List<Map<String, Object>> days = new ArrayList<>();
        double rain = 0;
        double et0 = 0;
        double etc = 0;
        double eta = 0;
        double deficit = 0;
        double excess = 0;
        int deficitDays = 0;
        int run = 0;
        int longest = 0;
        int missing = 0;
        int estimatedDays = 0;
        for (int i = 0; i < series.size(); i++) {
            Input in = series.get(i);
            Day day = balance.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", in.date().toString());
            m.put("rainMm", in.rainMm());
            m.put("et0Mm", round(in.et0Mm(), 2));
            m.put("etcMm", round(day.etcMm(), 2));
            m.put("etaMm", round(day.etaMm(), 2));
            m.put("storageMm", round(day.storageMm(), 2));
            m.put("deficitMm", round(day.deficitMm(), 2));
            m.put("excessMm", round(day.excessMm(), 2));
            m.put("rainMissing", in.rainMm() == null);
            m.put("et0Estimated", estimated.get(i));
            days.add(m);
            if (in.rainMm() != null) rain += in.rainMm();
            else missing++;
            if (estimated.get(i)) estimatedDays++;
            et0 += in.et0Mm();
            etc += day.etcMm();
            eta += day.etaMm();
            deficit += day.deficitMm();
            excess += day.excessMm();
            if (day.deficitMm() >= DEFICIT_DAY_MM) {
                deficitDays++;
                longest = Math.max(longest, ++run);
            } else {
                run = 0;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("totalRainMm", round(rain, 1));
        out.put("totalEt0Mm", round(et0, 1));
        out.put("totalEtcMm", round(etc, 1));
        out.put("totalEtaMm", round(eta, 1));
        out.put("totalDeficitMm", round(deficit, 1));
        out.put("totalExcessMm", round(excess, 1));
        Day last = balance.isEmpty() ? null : balance.get(balance.size() - 1);
        out.put("finalStorageMm", last == null ? null : round(last.storageMm(), 1));
        out.put("finalStoragePercent", last == null ? null : round(100 * last.storageMm() / cad, 1));
        out.put("deficitDays", deficitDays);
        out.put("maxConsecutiveDeficitDays", longest);
        out.put("missingRainDays", missing);
        out.put("estimatedEt0Days", estimatedDays);
        return out;
    }

    private String chart(List<Input> series, List<Day> balance) {
        List<DailyBarChart.Bar> bars = new ArrayList<>();
        List<DailyBarChart.Bar> storage = new ArrayList<>();
        List<DailyBarChart.Bar> deficit = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            LocalDate date = series.get(i).date();
            bars.add(new DailyBarChart.Bar(date, series.get(i).rainMm()));
            storage.add(new DailyBarChart.Bar(date, balance.get(i).storageMm()));
            deficit.add(new DailyBarChart.Bar(date, balance.get(i).deficitMm()));
        }
        List<DailyBarChart.Line> lines = bars.isEmpty() ? List.of()
                : List.of(new DailyBarChart.Line(STORAGE, storage), new DailyBarChart.Line(DEFICIT, deficit));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(chart.render(bars, lines, CHART_WIDTH, CHART_HEIGHT));
    }

    private Map<String, Object> notFound(Map<String, Object> partial, String message) {
        Map<String, Object> out = new HashMap<>(partial);
        out.put("found", false);
        out.put("message", message);
        out.putIfAbsent("cells", List.of());
        out.putIfAbsent("pixels", 0);
        out.put("days", List.of());
        out.put("deficitDays", 0);
        out.put("maxConsecutiveDeficitDays", 0);
        out.put("missingRainDays", 0);
        out.put("estimatedEt0Days", 0);
        out.put("chart", chart(List.of(), List.of()));
        return ordered(out);
    }

    private Map<String, Object> ordered(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (BlockPort port : definition().outputs()) out.put(port.name(), values.get(port.name()));
        return out;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value != null && !value.toString().isBlank()) return Double.parseDouble(value.toString().trim());
        return fallback;
    }
}
