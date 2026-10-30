package com.akvicor.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;

@ExtendWith(MockitoExtension.class)
class CoverAttachmentServiceTest {

    @Mock
    AttachmentService attachmentService;

    @InjectMocks
    CoverAttachmentService service;

    @ParameterizedTest
    @CsvSource({"自定义封面.jpeg,自定义封面.jpg", "自定义封面.jpg,自定义封面.jpg",
        "自定义封面.JPEG,自定义封面.jpg", "自定义封面.JPG,自定义封面.jpg",
        "Cover.Jpg,Cover.jpg", "Cover.JpEg,Cover.jpg",
        "Cover.PNG,Cover.png", "Cover.PnG,Cover.png",
        "Cover.WEBP,Cover.webp", "Cover.WebP,Cover.webp",
        "封面.jpeg.png,封面.jpeg.png"})
    void shouldPreserveFileBytesAndSetNameBeforeCreation(String displayName, String expectedName) {
        var bytes = new byte[] {0, (byte) 255, (byte) 128, 66};
        var source = new SimpleFilePart("provider-name.jpg",
            Flux.just(new DefaultDataBufferFactory().wrap(bytes)), MediaType.IMAGE_JPEG);
        when(attachmentService.upload(eq("admin"), eq("policy"), isNull(),
            any(FilePart.class), any()))
            .thenAnswer(invocation -> {
                var attachment = new Attachment();
                Consumer<Attachment> beforeCreating = invocation.getArgument(4);
                beforeCreating.accept(attachment);
                return Mono.just(attachment);
            });

        var attachment = service.upload("admin", "policy", " ", displayName, source).block();

        assertThat(attachment.getSpec().getDisplayName()).isEqualTo(expectedName);
        var file = ArgumentCaptor.forClass(FilePart.class);
        verify(attachmentService).upload(eq("admin"), eq("policy"), isNull(), file.capture(), any());
        assertThat(file.getValue().filename()).isEqualTo(expectedName);
        assertThat(file.getValue().headers().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        var buffer = DataBufferUtils.join(file.getValue().content()).block();
        assertThat(buffer).isNotNull();
        try {
            var actual = new byte[buffer.readableByteCount()];
            buffer.read(actual);
            assertThat(actual).containsExactly(bytes);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    @Test
    void shouldNormalizeGroupAndKeepMissingMediaTypeFallback() {
        var source = new SimpleFilePart("image", Flux.empty(), null);
        when(attachmentService.upload(eq("admin"), eq("policy"), eq("covers"),
            any(FilePart.class), any()))
            .thenReturn(Mono.just(new Attachment()));

        service.upload("admin", "policy", " covers ", "封面.png", source).block();

        var file = ArgumentCaptor.forClass(FilePart.class);
        verify(attachmentService).upload(eq("admin"), eq("policy"), eq("covers"),
            file.capture(), any());
        assertThat(file.getValue().headers().getContentType())
            .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @ParameterizedTest
    @CsvSource({"image/png,png", "image/jpeg,jpg", "image/jpg,jpg", "image/webp,webp"})
    void shouldSaveBackgroundImageWithDetectedExtensionAndAuthor(String mediaType, String extension) {
        var bytes = new byte[] {0, (byte) 255, 66};
        when(attachmentService.upload(eq("author"), eq("policy"), eq("covers"),
            any(FilePart.class), any()))
            .thenAnswer(invocation -> {
                var attachment = new Attachment();
                Consumer<Attachment> beforeCreating = invocation.getArgument(4);
                beforeCreating.accept(attachment);
                return Mono.just(attachment);
            });

        var attachment = service.uploadGenerated("author", "policy", "covers", " 标题/内容 ",
            new GeneratedImage(Base64.getEncoder().encodeToString(bytes), mediaType)).block();

        assertThat(attachment.getSpec().getDisplayName()).isEqualTo("标题-内容-00." + extension);
        var file = ArgumentCaptor.forClass(FilePart.class);
        verify(attachmentService).upload(eq("author"), eq("policy"), eq("covers"),
            file.capture(), any());
        var buffer = DataBufferUtils.join(file.getValue().content()).block();
        assertThat(buffer).isNotNull();
        try {
            var actual = new byte[buffer.readableByteCount()];
            buffer.read(actual);
            assertThat(actual).containsExactly(bytes);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
