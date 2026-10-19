package com.akvicor.aicover.service;

import org.springframework.util.StringUtils;

/**
 * 组装两步生成所需的提示词。
 *
 * <p>第一步把文章交给语言模型，第二步把语言模型输出的画面描述与用户提示词交给图片模型。
 */
final class CoverPromptComposer {

    /** 交给语言模型的文章正文最大字符数，避免请求体过大。 */
    static final int MAX_ARTICLE_LENGTH = 8000;

    private CoverPromptComposer() {
    }

    /**
     * 组装第一步的输入：交由语言模型阅读的文章内容。
     */
    static String composeArticle(String title, String content) {
        var builder = new StringBuilder();
        if (StringUtils.hasText(title)) {
            builder.append("文章标题：\n").append(title.strip());
        }
        var text = content == null ? "" : content.strip();
        if (!text.isEmpty()) {
            if (text.length() > MAX_ARTICLE_LENGTH) {
                text = text.substring(0, MAX_ARTICLE_LENGTH);
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("文章正文：\n").append(text);
        }
        return builder.toString().strip();
    }

    /**
     * 组装第二步的输入：交给图片模型的最终提示词。
     *
     * <p>用户提示词作为风格与构图约束放在前面，语言模型生成的画面描述作为画面内容放在后面。
     *
     * @param prompt 用户确认的提示词，可为空
     * @param description 语言模型生成的画面描述
     */
    static String composeImagePrompt(String prompt, String description) {
        var builder = new StringBuilder();
        if (StringUtils.hasText(prompt)) {
            builder.append(prompt.strip());
        }
        if (StringUtils.hasText(description)) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("画面内容：\n").append(description.strip());
        }
        return builder.toString().strip();
    }
}
