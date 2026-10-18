package run.halo.aicover.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.util.StringUtils;
import reactor.core.Exceptions;
import run.halo.aifoundation.exception.AiGenerationTimeoutException;
import run.halo.aifoundation.exception.IncompatibleModelTypeException;
import run.halo.aifoundation.exception.ModelDisabledException;
import run.halo.aifoundation.exception.ModelNotFoundException;
import run.halo.aifoundation.exception.ProviderDisabledException;
import run.halo.aifoundation.exception.UnsupportedModelCapabilityException;

/**
 * 生成任务的错误展示策略，统一处理异常包装、业务分类及外部说明的脱敏。
 */
final class CoverGenerationErrors {

    /** 限制界面中的诊断摘要长度，完整上下文通过任务日志定位。 */
    private static final int MAX_MESSAGE_LENGTH = 500;

    private static final Pattern URL = Pattern.compile(
        "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\"']+");
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)([\"']?(?:authorization|proxy-authorization|x-api-key|api[-_]?key|"
            + "access[-_]?token|refresh[-_]?token|private[-_]?key|client[-_]?secret|"
            + "password|passphrase|secret|token|credential)[\"']?\\s*[:=]\\s*)"
            + "(?:\"[^\"]*(?:\"|$)|'[^']*(?:'|$)|(?:Bearer|Basic)\\s+[^\\s,;\"'}\\]]+|"
            + "[^\\s,;\"'}\\]]+)");
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)\\b(Bearer|Basic)\\s+[^\\s,;\"'}\\]]+");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern JWT = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

    private CoverGenerationErrors() {
    }

    /**
     * 保留明确业务提示，其他错误显示安全摘要，无有效原因时使用任务 ID 兜底。
     */
    static String describe(Throwable error, String taskId) {
        var errors = errorChain(error);
        for (var current : errors) {
            var message = businessMessage(current);
            if (message != null) {
                return message;
            }
        }
        if (errors.stream().anyMatch(DataBufferLimitException.class::isInstance)) {
            // 该限制可能来自模型响应或本插件下载客户端，展示层不猜测数值和来源。
            return "图片响应超过当前缓冲上限，生成已中止。请查看后台日志确认实际限制。";
        }

        var cause = Exceptions.unwrap(error);
        var remaining = errors.size();
        while (remaining-- > 1 && cause.getCause() != null && (Exceptions.isRetryExhausted(cause)
            || cause.getClass() == RuntimeException.class || !StringUtils.hasText(cause.getMessage()))) {
            cause = Exceptions.unwrap(cause.getCause());
        }
        var message = safeMessage(cause.getMessage());
        if (message == null) {
            return "封面图生成失败，请根据任务 ID 查看后台日志：" + taskId;
        }
        if (cause.getClass() == IllegalStateException.class
            || cause.getClass() == IllegalArgumentException.class) {
            return message;
        }
        return "封面图生成失败：" + message;
    }

    private static String businessMessage(Throwable error) {
        if (error instanceof ModelNotFoundException) {
            return "所选模型不存在，请在插件设置中重新选择文章理解模型和图片生成模型，"
                + "或检查 AI Foundation 的模型配置";
        }
        if (error instanceof ModelDisabledException) {
            return "所选模型已被禁用，请在 AI Foundation 中启用对应模型";
        }
        if (error instanceof ProviderDisabledException) {
            return "所选模型所属服务商已被禁用，请在 AI Foundation 中启用该服务商";
        }
        if (error instanceof IncompatibleModelTypeException) {
            return "所选模型类型不符：文章理解需选择语言模型，出图需选择图片生成模型";
        }
        if (error instanceof UnsupportedModelCapabilityException) {
            return "所选模型不支持当前请求，请更换模型或简化提示词";
        }
        if (error instanceof AiGenerationTimeoutException) {
            return "模型调用超时，请重试或更换响应更快的模型";
        }
        return null;
    }

    private static List<Throwable> errorChain(Throwable error) {
        var result = new ArrayList<Throwable>();
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(error);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return result;
    }

    private static String safeMessage(String message) {
        if (!StringUtils.hasText(message) || message.startsWith("Retries exhausted:")) {
            return null;
        }
        // 先完整脱敏再截断，避免截断到凭据中间后失去其可识别的上下文。
        var safe = URL.matcher(message).replaceAll("[地址已隐藏]");
        safe = CREDENTIAL.matcher(safe).replaceAll("$1[已隐藏]");
        safe = AUTHORIZATION.matcher(safe).replaceAll("$1 [已隐藏]");
        safe = API_KEY.matcher(safe).replaceAll("[已隐藏]");
        safe = JWT.matcher(safe).replaceAll("[已隐藏]");
        safe = safe.replaceAll("\\s+", " ").strip();
        if (safe.length() > MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, MAX_MESSAGE_LENGTH) + "…";
        }
        return safe;
    }
}
