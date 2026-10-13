package ua.medicine.ui;

/** Colour theme the user picked; SYSTEM follows the desktop. */
public enum ThemeMode {
    SYSTEM("theme.system"),
    LIGHT("theme.light"),
    DARK("theme.dark");

    private final String messageKey;

    ThemeMode(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
