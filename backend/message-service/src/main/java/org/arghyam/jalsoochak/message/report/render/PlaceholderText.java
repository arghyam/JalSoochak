package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.report.catalog.Placeholder;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;

/** Plain {@code {name}} substitution in layout text: no expressions and no escaping. */
final class PlaceholderText {

    private PlaceholderText() {
    }

    /**
     * {@code text} with each catalogue placeholder replaced by its value.
     *
     * <p>A name the catalogue does not know is left as written, so a layout that slipped past
     * validation shows the stray token rather than losing the text around it.</p>
     *
     * @param values a placeholder's value; {@code null} prints as nothing
     * @return {@code ""} for {@code null} text
     */
    static String resolve(String text, Function<Placeholder, String> values) {
        if (text == null) {
            return "";
        }
        Matcher matcher = Placeholder.TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            Optional<Placeholder> placeholder = Placeholder.byKey(matcher.group(1));
            String replacement = placeholder.isPresent() ? nullToEmpty(values.apply(placeholder.get())) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
