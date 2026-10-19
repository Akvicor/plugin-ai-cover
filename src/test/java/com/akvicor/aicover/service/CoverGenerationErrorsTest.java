package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import run.halo.aifoundation.exception.AiGenerationTimeoutException;
import run.halo.aifoundation.exception.ModelNotFoundException;
import run.halo.aifoundation.exception.ProviderApiException;

class CoverGenerationErrorsTest {

    @Test
    void shouldExposeProviderReasonInsideRealRetryWrapper() {
        var error = Exceptions.retryExhausted("Retries exhausted: 1/1",
            new ProviderApiException("mock", 400, "模拟供应商拒绝请求"));

        var message = CoverGenerationErrors.describe(error, "task-id");

        assertThat(message).contains("模拟供应商拒绝请求", "400");
        assertThat(message).doesNotContain("Retries exhausted");
    }

    @Test
    void shouldKeepWrappedConfigurationAndTimeoutMessages() {
        var missing = Exceptions.retryExhausted("Retries exhausted: 1/1",
            new ModelNotFoundException("cover-model"));
        var timeout = Exceptions.retryExhausted("Retries exhausted: 1/1",
            new AiGenerationTimeoutException("total", "timeout"));

        assertThat(CoverGenerationErrors.describe(missing, "task-id")).contains("所选模型不存在");
        assertThat(CoverGenerationErrors.describe(timeout, "task-id")).contains("模型调用超时");
    }

    @ParameterizedTest
    @ValueSource(ints = {1048576, 20971520})
    void shouldExplainActualBufferFailuresWithoutGuessingLimit(int limit) {
        var buffer = new DataBufferLimitException("Exceeded limit on max bytes to buffer : " + limit);
        var error = Exceptions.retryExhausted("Retries exhausted: 1/1", buffer);

        var message = CoverGenerationErrors.describe(error, "task-id");

        assertThat(message).contains("缓冲上限");
        assertThat(message).doesNotContain("256KB", "AI Foundation", "Retries exhausted");
    }

    @Test
    void shouldFindBufferFailureInSuppressedExceptions() {
        var error = new IllegalStateException("request failed");
        error.addSuppressed(new DataBufferLimitException("buffer exceeded"));

        assertThat(CoverGenerationErrors.describe(error, "task-id")).contains("缓冲上限");
    }

    @Test
    void shouldRedactCredentialsAndCredentialBearingUrls() {
        // 全部为独立构造的测试文本，用于确认外部异常摘要不会透传凭据。
        var error = new ProviderApiException("mock", 401,
            "Authorization: Bearer demo-bearer-value; api_key=demo-key-value; "
                + "password='demo-password-value'; {\"accessToken\":\"demo-access-value\"}; "
                + "https://user:demo-url-password@example.test/path?token=demo-url-token; "
                + "standalone sk-demo-test-value");

        var message = CoverGenerationErrors.describe(error, "task-id");

        assertThat(message).contains("401", "已隐藏");
        assertThat(message).doesNotContain("demo-bearer-value", "demo-key-value",
            "demo-password-value", "demo-access-value", "demo-url-password",
            "demo-url-token", "sk-demo-test-value");
    }

    @Test
    void shouldLimitLongMessagesAfterRedaction() {
        var original = "reason ".repeat(500) + "api_key=demo-key-value";

        var message = CoverGenerationErrors.describe(new RuntimeException(original), "task-id");

        assertThat(message.length()).isLessThan(original.length());
        assertThat(message).endsWith("…");
        assertThat(message).doesNotContain("demo-key-value");
    }

    @Test
    void shouldRedactCredentialWhenProviderTruncatedQuotedValue() {
        var error = new ProviderApiException("mock", 400,
            "response: {\"api_key\":\"demo-truncated-value");

        assertThat(CoverGenerationErrors.describe(error, "task-id"))
            .contains("已隐藏").doesNotContain("demo-truncated-value");
    }

    @Test
    void shouldUseTaskIdWhenNoUsefulReasonIsAvailable() {
        assertThat(CoverGenerationErrors.describe(new RuntimeException(), "task-id"))
            .contains("封面图生成失败", "task-id");
    }

    @Test
    void shouldShowArticleValidationReasonWithoutStatusPrefix() {
        var error = new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "文章标题和正文均为空，请先保存文章内容后再生成");

        assertThat(CoverGenerationErrors.describe(error, "task-id"))
            .isEqualTo("文章标题和正文均为空，请先保存文章内容后再生成");
    }
}
