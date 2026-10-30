package ru.printlab.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "printlab.bot-enabled", havingValue = "true")
public class TelegramMenu {
    private final OutboxDao outbox;
    private final Clock clock;
    public TelegramMenu(OutboxDao outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void configure() {
        for (String language : List.of("", "ru")) {
            outbox.add("setMyShortDescription", Map.of("short_description", BotText.get("profile.short"), "language_code", language), clock.instant());
            outbox.add("setMyDescription", Map.of("description", BotText.get("profile.description"), "language_code", language), clock.instant());
        }
        outbox.add("setMyCommands", Map.of("commands", List.of(
                Map.of("command", "new", "description", "Новый заказ"),
                Map.of("command", "orders", "description", "Мои заказы"),
                Map.of("command", "help", "description", "Поддержка"))), clock.instant());
        outbox.add("setMyCommands", Map.of("scope", Map.of("type", "all_group_chats"), "commands", List.of(
                Map.of("command", "admin", "description", "Админ-меню"),
                Map.of("command", "orders", "description", "Заказы"))), clock.instant());
        outbox.add("setChatMenuButton", Map.of("menu_button", Map.of("type", "commands")), clock.instant());
    }
}
