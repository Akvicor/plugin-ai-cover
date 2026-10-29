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
}
