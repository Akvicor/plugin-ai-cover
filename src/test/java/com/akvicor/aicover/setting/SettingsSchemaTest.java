package com.akvicor.aicover.setting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** 验证设置表单的条件字段具有稳定身份，避免刷新后被前一个输入组件复用。 */
class SettingsSchemaTest {

    @Test
    void conditionalCompressionFieldsShouldHaveStableKeys() throws Exception {
        Map<String, Object> root;
        try (InputStream input = getClass().getResourceAsStream("/extensions/settings.yaml")) {
            assertThat(input).isNotNull();
            root = new Yaml().load(input);
        }
        var fields = formFields(root);
        assertThat(fields).allSatisfy(field -> {
            if (field.get("if") != null) {
                assertThat(field.get("key")).as("conditional field %s", field.get("name"))
                    .isNotNull();
            }
        });
        assertThat(fields.stream().map(field -> field.get("key")).filter(Objects::nonNull))
            .doesNotHaveDuplicates();

        var byName = fields.stream()
            .filter(field -> field.get("name") != null)
            .collect(Collectors.toMap(field -> (String) field.get("name"),
                Function.identity()));
        assertThat(byName).containsKeys("jpegCompressionMode", "jpegQuality",
            "webpCompressionMode", "webpQuality");
        assertThat(byName.get("jpegCompressionMode")).containsEntry("key", "jpegCompressionMode");
        assertThat(byName.get("jpegQuality")).containsEntry("key", "jpegQuality");
        assertThat(byName.get("webpCompressionMode")).containsEntry("key", "webpCompressionMode");
        assertThat(byName.get("webpQuality")).containsEntry("key", "webpQuality");
        assertThat(byName.get("jpegQuality").get("if"))
            .isEqualTo("$get(aiCoverJpegCompressionMode).value === 'QUALITY'");
        assertThat(byName.get("webpQuality").get("if"))
            .isEqualTo("$get(aiCoverWebpCompressionMode).value === 'QUALITY'");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> formFields(Map<String, Object> root) {
        var spec = (Map<String, Object>) root.get("spec");
        var forms = (List<Map<String, Object>>) spec.get("forms");
        assertThat(forms).hasSize(1);
        return (List<Map<String, Object>>) forms.getFirst().get("formSchema");
    }
}
