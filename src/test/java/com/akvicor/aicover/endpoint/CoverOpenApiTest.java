package com.akvicor.aicover.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webflux.core.configuration.SpringDocWebFluxConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import com.akvicor.aicover.service.CoverAttachmentService;
import com.akvicor.aicover.service.CoverGenerationTaskService;
import com.akvicor.aicover.service.background.BackgroundCoverService;
import com.akvicor.aicover.service.PostContextService;
import com.akvicor.aicover.setting.CoverSettingService;
import run.halo.app.extension.ReactiveExtensionClient;

/** 在内存 WebFlux 环境核对真实路由的 OpenAPI，并供客户端生成任务使用。 */
class CoverOpenApiTest {

    @Test
    void shouldDescribeTaskProgressAndMultipartUpload() {
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpringDocConfiguration.class,
                SpringDocConfigProperties.class, SpringDocSpecPropertiesConfiguration.class,
                SpringDocWebFluxConfiguration.class))
            .withUserConfiguration(DocsConfiguration.class)
            .withPropertyValues("springdoc.api-docs.version=OPENAPI_3_0")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var bytes = WebTestClient.bindToApplicationContext(context).build()
                    .get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();
                var document = Json.mapper().readTree(bytes);
                assertThat(document.path("openapi").asText()).startsWith("3.0.");
                var prefix = "/apis/console.api.aicover.halo.run/v1alpha1/";
                assertThat(document.path("paths").path(prefix + "covers/tasks").has("get")).isTrue();
                assertThat(document.path("paths").has(prefix + "covers/background")).isTrue();
                assertThat(document.path("paths").has(prefix + "covers/apply")).isFalse();
                var task = document.at("/components/schemas/CoverTaskResponse/properties");
                assertThat(task.has("stage")).isTrue();
                assertThat(task.has("source")).isTrue();
                assertThat(task.has("imageAvailable")).isTrue();
                assertThat(task.has("finishedAt")).isTrue();
                assertThat(document.at("/components/schemas/BackgroundCoverRequest/properties/mode/enum")
                    .toString()).contains("FILL", "REPLACE");
                assertThat(document.path("paths").path(prefix + "covers/upload").path("post")
                    .path("requestBody").path("content").has("multipart/form-data")).isTrue();
                var destination = Path.of("build/api-docs/openapi/v3_0/aiCoverApis.json");
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @EnableConfigurationProperties
    static class DocsConfiguration {

        @Bean
        OpenAPI openApi() {
            return new OpenAPI().info(new Info().title("AI Cover").version("v1alpha1"));
        }

        @Bean
        RouterFunction<ServerResponse> coverRoutes() {
            var endpoint = new CoverConsoleEndpoint(mock(CoverGenerationTaskService.class),
                mock(BackgroundCoverService.class), mock(CoverAttachmentService.class),
                mock(CoverSettingService.class), mock(PostContextService.class),
                mock(ReactiveExtensionClient.class));
            return RouterFunctions.route()
                .path("/apis/console.api.aicover.halo.run/v1alpha1", builder -> builder.add(endpoint.endpoint()))
                .build();
        }
    }
}
