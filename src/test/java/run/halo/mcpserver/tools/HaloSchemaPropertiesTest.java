package run.halo.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;

class HaloSchemaPropertiesTest {

    @Test
    void projectsTypesConstraintsAndJavadocsFromHaloModels() {
        assertThat(HaloSchemaProperties.property(Post.PostSpec.class, "title"))
                .containsEntry("type", "string")
                .containsEntry("minLength", 1)
                .containsEntry("description", "Display title of the post.");
        assertThat(HaloSchemaProperties.property(Post.PostSpec.class, "visible"))
                .containsEntry("type", "string")
                .containsEntry("enum", List.of("PUBLIC", "INTERNAL", "PRIVATE"))
                .containsEntry(
                        "description",
                        "Visibility used by theme-side and public REST queries; anonymous clients only receive PUBLIC posts.");
        assertThat(HaloSchemaProperties.property(Attachment.AttachmentSpec.class, "size"))
                .containsEntry("type", "integer")
                .containsEntry("minimum", BigDecimal.ZERO)
                .containsEntry("description", "Size of the attachment in bytes.");
        assertThat(HaloSchemaProperties.property(Metadata.class, "version"))
                .containsEntry("type", "integer")
                .containsEntry("format", "int64")
                .containsEntry("description", "Current version of the Extension. It will be bumped up every update.")
                .doesNotContainKey("nullable");
    }
}
