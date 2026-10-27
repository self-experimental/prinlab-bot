package ru.printlab.telegram;

import java.util.Locale;
import java.util.ResourceBundle;

public final class BotText {
    private static final Locale LOCALE = Locale.forLanguageTag("ru");
    private static final ResourceBundle TEXT = ResourceBundle.getBundle("bot-texts", LOCALE);

    private BotText() {
    }

    public static String get(String key) {
        return TEXT.getString(key);
    }
}
