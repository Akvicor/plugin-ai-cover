package com.akvicor.aicover.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 读取插件设置。设置值存储于插件 configMap 中，由 Halo 的 {@link ReactiveSettingFetcher} 提供。
 */
@Component
@RequiredArgsConstructor
public class CoverSettingService {

    /** 与 settings.yaml 中 form.group 保持一致。 */
    public static final String GROUP = "default";

    private final ReactiveSettingFetcher settingFetcher;

    /**
     * 获取当前插件设置，未配置时返回空的设置对象而不是错误。
     */
    public Mono<CoverSettings> getSettings() {
        return settingFetcher.fetch(GROUP, CoverSettings.class)
            .defaultIfEmpty(new CoverSettings());
    }
}
