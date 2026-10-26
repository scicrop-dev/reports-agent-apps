package com.infinitestack.reports.blocks.car;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.infinitestack.reports.engine.block.BlockQueries;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code car-property-map} against a database with the CAR agent app's gold tables (the shared dev
 * DB). Skipped unless {@code CAR_TEST_DB_URL} is set; uses CAR_TEST_DB_USER / CAR_TEST_DB_PASSWORD.
 * Read-only, per-property queries. The PNGs land in {@code target/car-property-map/} for a look.
 */
class CarPropertyMapDevDbTest {

    /** Has APPs and overlapping neighbours. */
    private static final String WITH_APP = "BA-2915601-735B9BEE97F6498387B672DAA9506DF1";
    /** Has native vegetation, legal reserve and consolidated area. */
    private static final String WITH_LEGAL_RESERVE = "AC-1200138-1A00CC768EA041C98A9CEA1790C0C90F";

    private static HikariDataSource dataSource;
    private static CarPropertyMapBlock block;

    @BeforeAll
    static void connect() {
        String url = System.getenv("CAR_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "CAR_TEST_DB_URL not set");
        System.setProperty("java.awt.headless", "true");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(System.getenv("CAR_TEST_DB_USER"));
        dataSource.setPassword(System.getenv("CAR_TEST_DB_PASSWORD"));
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(2);
        block = new CarPropertyMapBlock(new BlockQueries(dataSource));
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void mapsPropertyWithApp() throws Exception {
        BufferedImage image = render(WITH_APP, Map.of("codImovel", WITH_APP), "app.png", List.of("APPS"));
        assertThat(image.getWidth()).isEqualTo(1000);
        assertThat(image.getHeight()).isEqualTo(700);
    }

    @Test
    void mapsPropertyWithLegalReserve() throws Exception {
        BufferedImage image = render(WITH_LEGAL_RESERVE,
                Map.of("codImovel", WITH_LEGAL_RESERVE, "width", 800, "height", 600), "legal-reserve.png",
                List.of("VEGETACAO_NATIVA", "AREA_CONSOLIDADA", "RESERVA_LEGAL"));
        assertThat(image.getWidth()).isEqualTo(800);
    }

    @Test
    void unknownPropertyFails() {
        String missing = "BA-2915601-00000000000000000000000000000000";
        assertThatThrownBy(() -> block.execute(Map.of("codImovel", missing), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAR property " + missing + " not found");
    }

    @SuppressWarnings("unchecked")
    private static BufferedImage render(String cod, Map<String, Object> inputs, String file, List<String> layers)
            throws Exception {
        long start = System.nanoTime();
        Map<String, Object> out = block.execute(inputs, null);
        long millis = (System.nanoTime() - start) / 1_000_000;

        String image = (String) out.get("image");
        assertThat(image).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(image.substring("data:image/png;base64,".length()));
        Path dir = Path.of("target", "car-property-map");
        Files.createDirectories(dir);
        Files.write(dir.resolve(file), png);
        System.out.printf("%s: %d bytes PNG, %d ms, legend %s%n", cod, png.length, millis, out.get("legend"));

        List<Map<String, Object>> legend = (List<Map<String, Object>>) out.get("legend");
        assertThat(legend).extracting(e -> e.get("layer")).containsAll(layers);
        assertThat(png.length).isLessThan(400_000);
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
