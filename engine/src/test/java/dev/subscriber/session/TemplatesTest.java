package dev.subscriber.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplatesTest {

    @Test
    void placeholdersAreFilledInOnePass() {
        assertThat(Templates.fill("a {{x}} b {{y}} c {{x}}", Map.of("x", "1", "y", "2"))).isEqualTo("a 1 b 2 c 1");
    }

    @Test
    void aValueThatLooksLikeAPlaceholderIsInsertedAsItIsAndNeverExpanded() {
        String out = Templates.fill("path={{repo}} agent={{agent}}", Map.of("repo", "C:/{{agent}}/{{unknown}}", "agent", "security-fixer"));
        assertThat(out).isEqualTo("path=C:/{{agent}}/{{unknown}} agent=security-fixer");
    }

    @Test
    void backslashesAndDollarSignsInValuesAreKeptLiterally() {
        assertThat(Templates.fill("{{p}}", Map.of("p", "C:\\a\\$1\\${x}"))).isEqualTo("C:\\a\\$1\\${x}");
    }

    @Test
    void aPlaceholderWithoutAValueIsAnErrorNotLeftInTheText() {
        assertThatThrownBy(() -> Templates.fill("x {{missing}} y", Map.of("other", "1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("{{missing}}");
    }

    @Test
    void textWithoutPlaceholdersIsUnchanged() {
        assertThat(Templates.fill("plain {text} and { {x} }", Map.of())).isEqualTo("plain {text} and { {x} }");
    }
}
