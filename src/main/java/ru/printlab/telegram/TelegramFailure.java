package ru.printlab.telegram;

public class TelegramFailure extends RuntimeException {
    private final boolean permanent;
    private final int retryAfter;

    public TelegramFailure(boolean permanent, int retryAfter) {
        super("Telegram API request failed; credentials and request URL omitted");
        this.permanent = permanent;
        this.retryAfter = retryAfter;
    }

    public boolean permanent() {
        return permanent;
    }

    public int retryAfter() {
        return retryAfter;
    }
}
