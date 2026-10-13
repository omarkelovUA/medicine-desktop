package ua.medicine.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineCssTest {

    /** The label's inside/outside choice is made from its width; the outside state must not change that width. */
    @Test
    void outsideLabelChangesOnlyColour() throws IOException {
        String css;
        try (InputStream in = TimelineCssTest.class.getResourceAsStream("/ua/medicine/ui/theme.css")) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\\.timeline-bar-label:outside\\s*\\{([^}]*)}").matcher(css);
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).doesNotContain("-fx-font");
    }
}
