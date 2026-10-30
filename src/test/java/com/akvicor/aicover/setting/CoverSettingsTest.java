package com.akvicor.aicover.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证旧设置兼容性及各图片格式选项的持久化值契约。 */
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

    @Test
    void shouldDefaultJpegAndWebpToOriginalWithIndependentQuality() throws Exception {
        var haloMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var json : new String[] {"{}", "{\"jpegCompressionMode\":null,\"webpCompressionMode\":null,"
            + "\"jpegQuality\":null,\"webpQuality\":null}"}) {
            for (var settings : new CoverSettings[] {mapper.readValue(json, CoverSettings.class),
                haloMapper.readValue(json, CoverSettings.class)}) {
                assertThat(settings.getJpegCompressionMode()).isEqualTo(QualityCompressionMode.NONE);
                assertThat(settings.getWebpCompressionMode()).isEqualTo(QualityCompressionMode.NONE);
                assertThat(settings.getJpegQuality()).isEqualTo(80);
                assertThat(settings.getWebpQuality()).isEqualTo(80);
            }
        }
        var settings = haloMapper.readValue("{\"jpegCompressionMode\":\"QUALITY\",\"jpegQuality\":65,"
            + "\"webpQuality\":90}", CoverSettings.class);
        assertThat(settings.getJpegCompressionMode()).isEqualTo(QualityCompressionMode.QUALITY);
        assertThat(settings.getWebpCompressionMode()).isEqualTo(QualityCompressionMode.NONE);
        assertThat(settings.getJpegQuality()).isEqualTo(65);
        assertThat(settings.getWebpQuality()).isEqualTo(90);
    }

    @Test
    void shouldValidateQualityRangeAndModesWithBothMappers() throws Exception {
        var haloMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var field : new String[] {"jpegQuality", "webpQuality"}) {
            for (var value : new String[] {"1", "80", "100", "\"80\""}) {
                var json = "{\"" + field + "\":" + value + "}";
                assertThat(mapper.readValue(json, CoverSettings.class)).isNotNull();
                assertThat(haloMapper.readValue(json, CoverSettings.class)).isNotNull();
            }
            for (var value : new String[] {"0", "-1", "101", "20.5"}) {
                var json = "{\"" + field + "\":" + value + "}";
                assertThatThrownBy(() -> mapper.readValue(json, CoverSettings.class)).hasMessageContaining("1–100");
                assertThatThrownBy(() -> haloMapper.readValue(json, CoverSettings.class)).hasMessageContaining("1–100");
            }
        }
        for (var field : new String[] {"jpegCompressionMode", "webpCompressionMode"}) {
            var json = "{\"" + field + "\":\"unknown\"}";
            assertThatThrownBy(() -> mapper.readValue(json, CoverSettings.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
            assertThatThrownBy(() -> haloMapper.readValue(json, CoverSettings.class))
                .isInstanceOf(tools.jackson.databind.exc.InvalidFormatException.class);
        }
    }
}
