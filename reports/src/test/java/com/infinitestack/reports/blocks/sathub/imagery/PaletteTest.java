package com.infinitestack.reports.blocks.sathub.imagery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaletteTest {

    @Test
    void interpolatesLinearlyBetweenStopsAndClamps() {
        Palette p = Palette.parse("test", """
                [{"pos": 1.0, "color": "#ffffff"}, {"pos": 0.5, "color": "#000000"}]""");
        assertThat(p.stops()).extracting(Palette.Stop::pos).containsExactly(0.5, 1.0);
        assertThat(p.colorAt(0.75)).isEqualTo(0x808080);
        assertThat(p.colorAt(0.2)).isZero(); // before the first stop
        assertThat(p.colorAt(-3)).isZero();
        assertThat(p.colorAt(7)).isEqualTo(0xffffff);
    }

    @Test
    void builtInsMatchTheSatelliteHubStops() {
        Palette veg = Palette.vegetation();
        assertThat(veg.stops()).hasSize(8);
        assertThat(veg.colorAt(0.0)).isEqualTo(0x785a3c);
        assertThat(veg.colorAt(0.3)).isEqualTo(0x785a3c);
        assertThat(veg.colorAt(0.8)).isEqualTo(0x3ca02d);
        assertThat(veg.colorAt(1.0)).isEqualTo(0x0a5019);
        Palette water = Palette.water();
        assertThat(water.stops()).hasSize(5);
        assertThat(water.colorAt(0.5)).isEqualTo(0xf5f5f5);
        assertThat(water.colorAt(1.0)).isEqualTo(0x018571);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> Palette.parse("x", "[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Palette.parse("x", "{")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Palette.parse("x", "[{\"pos\": 2, \"color\": \"#000000\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Palette.parse("x", "[{\"pos\": 0, \"color\": \"red\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Palette.parse("x", "[{\"color\": \"#000000\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
