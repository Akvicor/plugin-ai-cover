package com.akvicor.aicover.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证旧设置兼容性及 PNG 选项的持久化值契约。 */
class CoverSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void existingSettingsShouldKeepCompressionDisabled() throws Exception {
        assertThat(mapper.readValue("{}", CoverSettings.class).getPngCompressionMode())
            .isEqualTo(PngCompressionMode.NONE);
        assertThat(mapper.readValue("{\"pngCompressionMode\":null}", CoverSettings.class)
            .getPngCompressionMode()).isEqualTo(PngCompressionMode.NONE);
    }

    @Test
    void shouldReadBothPngModesAndRejectUnknownValues() throws Exception {
        for (var mode : PngCompressionMode.values()) {
            var settings = mapper.readValue("{\"pngCompressionMode\":\"" + mode + "\"}", CoverSettings.class);
            assertThat(settings.getPngCompressionMode()).isEqualTo(mode);
        }
        assertThatThrownBy(() -> mapper.readValue("{\"pngCompressionMode\":\"unknown\"}", CoverSettings.class))
            .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    @Test
    void shouldValidateConcurrencyWithBothHaloAndLegacyMappers() throws Exception {
        var haloMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(mapper.readValue("{}", CoverSettings.class).getMaxConcurrentGenerations()).isEqualTo(8);
        assertThat(haloMapper.readValue("{}", CoverSettings.class).getMaxConcurrentGenerations()).isEqualTo(8);
        for (var value : new String[] {"1", "8", "1024", "\"8\""}) {
            var json = "{\"maxConcurrentGenerations\":" + value + "}";
            assertThat(mapper.readValue(json, CoverSettings.class).getMaxConcurrentGenerations()).isBetween(1, 1024);
            assertThat(haloMapper.readValue(json, CoverSettings.class).getMaxConcurrentGenerations()).isBetween(1, 1024);
        }
        for (var value : new String[] {"0", "-1", "1025", "1.5"}) {
            var json = "{\"maxConcurrentGenerations\":" + value + "}";
            assertThatThrownBy(() -> mapper.readValue(json, CoverSettings.class)).hasMessageContaining("1–1024");
            assertThatThrownBy(() -> haloMapper.readValue(json, CoverSettings.class)).hasMessageContaining("1–1024");
        }
    }
}
