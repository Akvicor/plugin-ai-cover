package com.akvicor.aicover.endpoint.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;

class UploadCoverRequestTest {

    private MultiValueMap<String, Part> parts;

    @BeforeEach
    void setUp() {
        parts = new LinkedMultiValueMap<>();
        parts.add("file", new SimpleFilePart("generated.png", Flux.empty(), MediaType.IMAGE_PNG));
        parts.add("displayName", field("自定义完整名称.png"));
        parts.add("policyName", field("local-policy"));
    }

    @Test
    void shouldReadFileAndAttachmentFields() {
        parts.add("groupName", field("covers"));
        parts.add("taskId", field("task-id"));

        var request = UploadCoverRequest.fromParts(parts);

        assertThat(request.file()).isSameAs(parts.getFirst("file"));
        assertThat(request.displayName()).isEqualTo("自定义完整名称.png");
        assertThat(request.policyName()).isEqualTo("local-policy");
        assertThat(request.groupName()).isEqualTo("covers");
        assertThat(request.taskId()).isEqualTo("task-id");
    }

    @Test
    void shouldAllowMissingGroup() {
        assertThat(UploadCoverRequest.fromParts(parts).groupName()).isNull();
        assertThat(UploadCoverRequest.fromParts(parts).taskId()).isNull();
    }

    @Test
    void shouldRejectMissingOrNonFileContent() {
        parts.remove("file");
        assertBadRequest();

        parts.add("file", field("base64-is-not-a-file"));
        assertBadRequest();
    }

    @ParameterizedTest
    @ValueSource(strings = {"displayName", "policyName"})
    void shouldRejectMissingOrBlankRequiredField(String name) {
        parts.remove(name);
        assertBadRequest();

        parts.add(name, field(" "));
        assertBadRequest();
    }

    @Test
    void shouldRejectFileUsedAsTextField() {
        parts.add("groupName", parts.getFirst("file"));
        assertBadRequest();
    }

    private void assertBadRequest() {
        assertThatThrownBy(() -> UploadCoverRequest.fromParts(parts))
            .isInstanceOfSatisfying(ServerWebInputException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static FormFieldPart field(String value) {
        var field = mock(FormFieldPart.class);
        when(field.value()).thenReturn(value);
        return field;
    }
}
