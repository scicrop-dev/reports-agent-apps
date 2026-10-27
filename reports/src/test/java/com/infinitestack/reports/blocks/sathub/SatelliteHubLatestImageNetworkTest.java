package com.infinitestack.reports.blocks.sathub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.reports.blocks.car.CarPropertyGeometryBlock;
import com.infinitestack.reports.blocks.sathub.imagery.EarthSearchClient;
import com.infinitestack.reports.blocks.sathub.imagery.HttpCogReader;
import com.infinitestack.reports.blocks.sathub.imagery.HttpRangeFetcher;
import com.infinitestack.reports.engine.block.BlockQueries;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Runs {@code sathub-latest-image} for real: the geometry and the index definitions from the dev
 * database, the scenes from Earth Search and the bands from the Sentinel-2 COGs on AWS. Skipped unless
 * {@code SENTINEL_NETWORK_TEST=1} and {@code CAR_TEST_DB_URL} (with {@code CAR_TEST_DB_USER} /
 * {@code CAR_TEST_DB_PASSWORD}) are set. Writes the PNGs to {@code target/latest-image/} to look at.
 */
class SatelliteHubLatestImageNetworkTest {

    static final String PROPERTY = "AC-1200302-7ECA40E36E154380BC739F9CB7E33DE0";

    @Test
    @SuppressWarnings("unchecked")
    void latestImageOfARealProperty() throws Exception {
        String url = System.getenv("CAR_TEST_DB_URL");
        assumeTrue("1".equals(System.getenv("SENTINEL_NETWORK_TEST")), "SENTINEL_NETWORK_TEST not set");
        assumeTrue(url != null && !url.isBlank(), "CAR_TEST_DB_URL not set");
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(url);
            dataSource.setUsername(System.getenv("CAR_TEST_DB_USER"));
            dataSource.setPassword(System.getenv("CAR_TEST_DB_PASSWORD"));
            dataSource.setReadOnly(true);
            dataSource.setMaximumPoolSize(2);
            BlockQueries queries = new BlockQueries(dataSource);
            Map<String, Object> geometry = (Map<String, Object>) new CarPropertyGeometryBlock(queries)
                    .execute(Map.of("codImovel", PROPERTY), null).get("geometry");

            SatelliteHubLatestImageBlock block = new SatelliteHubLatestImageBlock(queries,
                    new EarthSearchClient("https://earth-search.aws.element84.com/v1"),
                    new HttpCogReader(new HttpRangeFetcher(Duration.ofSeconds(30))));
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("geometry", geometry);
            inputs.put("indices", "ndvi,evi,ndmi,ndre");

            long start = System.currentTimeMillis();
            Map<String, Object> out = SatelliteHubLatestImageBlockTest.run(block, inputs);
            long elapsed = System.currentTimeMillis() - start;

            Path dir = Path.of("target", "latest-image");
            Files.createDirectories(dir);
            if (Boolean.TRUE.equals(out.get("found"))) {
                write(dir.resolve("true-color.png"), (String) out.get("trueColor"));
                for (Map<String, Object> index : (List<Map<String, Object>>) out.get("indices")) {
                    write(dir.resolve(index.get("slug") + ".png"), (String) index.get("image"));
                    System.out.println(index.get("slug") + ": mean " + index.get("mean") + ", p10-p90 " + index.get("p10")
                            + " - " + index.get("p90") + ", valid " + index.get("validPercent") + "%");
                }
                assertEquals(4, ((List<?>) out.get("indices")).size());
            }
            System.out.println("sathub-latest-image (" + elapsed + " ms): found " + out.get("found") + ", message " + out.get("message")
                    + ", scene " + out.get("scene") + ", area cloud " + out.get("areaCloudPercent") + "%, "
                    + out.get("widthPx") + "x" + out.get("heightPx") + " px of " + out.get("pixelSizeM") + " m, tried "
                    + out.get("candidatesTried") + ", skipped " + out.get("skipped"));
        }
    }

    private static void write(Path file, String dataUri) throws Exception {
        Files.write(file, Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1)));
    }
}
