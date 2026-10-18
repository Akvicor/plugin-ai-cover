package run.halo.aicover.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
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

    @Test
    void shouldPreserveFileBytesAndSetNameBeforeCreation() {
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

        var attachment = service.upload("admin", "policy", " ", "自定义封面.jpg", source).block();

        assertThat(attachment.getSpec().getDisplayName()).isEqualTo("自定义封面.jpg");
        var file = ArgumentCaptor.forClass(FilePart.class);
        verify(attachmentService).upload(eq("admin"), eq("policy"), isNull(), file.capture(), any());
        assertThat(file.getValue().filename()).isEqualTo("自定义封面.jpg");
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
}
