package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoverPromptComposerTest {

    @Test
    void shouldComposeArticleWithTitleAndContent() {
        var result = CoverPromptComposer.composeArticle("我的文章", "正文内容");

        assertThat(result).isEqualTo("""
            文章标题：
            我的文章

            文章正文：
            正文内容""");
    }

    @Test
    void shouldComposeArticleIgnoringBlankParts() {
        assertThat(CoverPromptComposer.composeArticle("  ", null)).isEmpty();
        assertThat(CoverPromptComposer.composeArticle("标题", " ")).isEqualTo("文章标题：\n标题");
    }

    @Test
    void shouldTruncateArticleToMaxLength() {
        var content = "a".repeat(CoverPromptComposer.MAX_ARTICLE_LENGTH + 500);

        var result = CoverPromptComposer.composeArticle("标题", content);

        assertThat(result).contains(
            "文章正文：\n" + "a".repeat(CoverPromptComposer.MAX_ARTICLE_LENGTH));
        assertThat(result).doesNotContain(
            "a".repeat(CoverPromptComposer.MAX_ARTICLE_LENGTH + 1));
    }

    @Test
    void shouldPutPromptBeforeDescription() {
        var result = CoverPromptComposer.composeImagePrompt("二次元插画风格", "银发少女站在服务器机房中");

        assertThat(result).isEqualTo("""
            二次元插画风格

            画面内容：
            银发少女站在服务器机房中""");
    }

    @Test
    void shouldComposeImagePromptIgnoringBlankParts() {
        assertThat(CoverPromptComposer.composeImagePrompt(null, "画面")).isEqualTo("画面内容：\n画面");
        assertThat(CoverPromptComposer.composeImagePrompt("风格", " ")).isEqualTo("风格");
        assertThat(CoverPromptComposer.composeImagePrompt("  ", null)).isEmpty();
    }
}
