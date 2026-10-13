package ua.medicine.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** Ukrainian UI strings. Bundle values never contain ASCII apostrophes (MessageFormat would drop them). */
public final class Messages {

    public static final Locale LOCALE = Locale.of("uk", "UA");
    private static final String BASE_NAME = "ua.medicine.i18n.messages";

    private Messages() {
    }

    public static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BASE_NAME, LOCALE);
    }

    public static String get(String key, Object... args) {
        String value = bundle().getString(key);
        return args.length == 0 ? value : new MessageFormat(value, LOCALE).format(args);
    }
}
