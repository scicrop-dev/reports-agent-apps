package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * UTM against GDAL 3.8 / PROJ ({@code echo "lon lat" | gdaltransform -s_srs "+proj=longlat +datum=WGS84"
 * -t_srs EPSG:<epsg> -output_xy}): both hemispheres, the central meridian, the zone edges and points
 * up to 5 degrees out of the zone (a CAR property crossing a zone boundary still reads one scene).
 */
class UtmTest {

    private static final double MM = 0.001;

    @ParameterizedTest
    @CsvSource({
            "32719, -70.2, -8.2, 367816.89026743, 9093394.28038154",
            "32719, -71.9, -0.5, 177176.037446751, 9944663.62243432",
            "32719, -74.5, -15.3, -91245.8001737756, 8300987.37507068",
            "32719, -66.6, -33.0, 724224.878692425, 6346154.43758334",
            "32722, -48.0, -30.0, 789409.653236465, 6677424.09561524",
            "32722, -53.7, -2.0, 199627.728791819, 9778691.87100455",
            "32633, 15.0, 45.0, 500000.000000001, 4982950.40022655",
            "32633, 11.2, 61.5, 297793.053141023, 6824377.69420182",
            "32618, -79.9, 0.3, -45910.2131480958, 33281.4698270082",
            "32760, 179.5, -70.0, 595404.970462425, 2232170.59236948",
            "32601, -177.0, 80.0, 500000, 8881585.8159881"})
    void matchesGdalAndRoundTrips(int epsg, double lon, double lat, double x, double y) {
        double[] xy = Utm.forward(lon, lat, epsg);
        assertThat(xy[0]).isCloseTo(x, offset(MM));
        assertThat(xy[1]).isCloseTo(y, offset(MM));

        double[] lonLat = Utm.inverse(x, y, epsg);
        // 1e-8 degree is about 1 mm on the ground
        assertThat(lonLat[0]).isCloseTo(lon, offset(1e-8));
        assertThat(lonLat[1]).isCloseTo(lat, offset(1e-8));

        double[] back = Utm.forward(lonLat[0], lonLat[1], epsg);
        assertThat(back[0]).isCloseTo(x, offset(1e-6));
        assertThat(back[1]).isCloseTo(y, offset(1e-6));
    }

    @Test
    void rejectsNonUtmEpsg() {
        assertThat(Utm.isUtm(32719)).isTrue();
        assertThat(Utm.isUtm(4674)).isFalse();
        assertThatThrownBy(() -> Utm.forward(-50, -10, 31983)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Utm.inverse(500000, 9000000, 4326)).isInstanceOf(IllegalArgumentException.class);
    }
}
