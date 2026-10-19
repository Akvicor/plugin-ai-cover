package com.akvicor.aicover;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * AI 封面生成插件主类，负责插件生命周期管理。
 *
 * @author akvicor
 * @since 1.0.0
 */
@Component
public class AiCoverPlugin extends BasePlugin {

    public AiCoverPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
