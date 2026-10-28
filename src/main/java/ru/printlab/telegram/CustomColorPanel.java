package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.catalog.CustomColorService;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static ru.printlab.telegram.Notifications.button;

@Component
public class CustomColorPanel {
    private final CustomColorService colors;
    private final DialogDao dialogs;
    private final Notifications messages;
    private final Json json;
    public CustomColorPanel(CustomColorService colors, DialogDao dialogs, Notifications messages, Json json) {
        this.colors = colors;
        this.dialogs = dialogs;
        this.messages = messages;
        this.json = json;
    }

    public void callback(long user, long chat, String data) {
        String[] parts = data.split(":", 3);
        if (parts[1].equals("accept")) {
            var request = colors.pending(user, UUID.fromString(parts[2]));
            dialogs.save(user, chat, "CC_EDIT", "name", request.orderId(), json.write(Map.of("request", request.id().toString())));
            messages.buttons(chat, "Предложенный цвет: " + request.description() + "\n\nВведите короткое название для заказа, до 80 символов.", List.of(button("Отмена", "op:open:" + request.orderId())));
            return;
        }
        if (parts[1].equals("reject")) {
            colors.reject(user, UUID.fromString(parts[2]));
            dialogs.clear(user, chat);
            messages.text(chat, "Отказ отправлен клиенту.");
            return;
        }
        BotDialog dialog = dialogs.get(user, chat);
        if (dialog == null || !dialog.kind().equals("CC_EDIT")) throw new BusinessException("Откройте предложение цвета заново.");
        Map<String, String> values = values(dialog.payload());
        if (parts[1].equals("kg") && dialog.step().equals("grams")) {
            input(user, chat, "1000");
        } else if (parts[1].equals("save") && dialog.step().equals("review")) {
            colors.approve(user, UUID.fromString(values.get("request")), values.get("name"), new BigDecimal(values.get("price")), Integer.parseInt(values.get("grams")));
            dialogs.clear(user, chat);
            messages.text(chat, "Цвет согласован. Клиент предупреждён о покупке пластика и возможном увеличении срока.");
        } else throw new BusinessException("Эта кнопка уже неактуальна.");
    }

    public boolean input(long user, long chat, String text) {
        BotDialog dialog = dialogs.get(user, chat);
        if (dialog == null || !dialog.kind().equals("CC_EDIT")) return false;
        colors.pending(user, UUID.fromString(values(dialog.payload()).get("request")));
        if (text.isBlank()) throw new BusinessException("Для согласования цвета нужно текстовое сообщение.");
        Map<String, String> values = values(dialog.payload());
        String next;
        try {
            switch (dialog.step()) {
                case "name" -> {
                    if (text.length() > 80) throw new BusinessException("Название должно быть до 80 символов.");
                    next = "price";
                }
                case "price" -> {
                    BigDecimal amount = new BigDecimal(text.replace(',', '.'));
                    if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(new BigDecimal("1000000")) > 0)
                        throw new BusinessException("Укажите стоимость катушки больше 0 и не больше 1000000 ₽, до двух знаков после запятой.");
                    text = amount.toPlainString();
                    next = "grams";
                }
                case "grams" -> {
                    int grams = Integer.parseInt(text);
                    if (grams < 1 || grams > 100000) throw new BusinessException("Укажите массу пластика от 1 до 100000 г.");
                    next = "review";
                }
                default -> throw new BusinessException("Подтвердите согласование кнопкой под сообщением.");
            }
        } catch (NumberFormatException e) {
            throw new BusinessException("Не получилось распознать число. Введите его ещё раз.");
        }
        values.put(dialog.step(), text);
        dialogs.save(user, chat, "CC_EDIT", next, dialog.orderId(), json.write(values));
        String prompt = switch (next) {
            case "price" -> "Введите закупочную цену катушки в рублях.";
            case "grams" -> "Сколько граммов пластика в катушке без самой катушки?";
            default -> "Цвет: " + values.get("name") + "\nКатушка: " + values.get("price") + " ₽ / " + values.get("grams") + " г\n\nСогласовать цвет для этого заказа? В общий доступ каталога он попадёт только после отдельного включения оператором.";
        };
        var buttons = new java.util.ArrayList<Map<String, String>>();
        if (next.equals("grams")) buttons.add(button("1000 г", "cc:kg"));
        if (next.equals("review")) buttons.add(button("Согласовать цвет", "cc:save"));
        buttons.add(button("Отмена", "op:open:" + dialog.orderId()));
        messages.buttons(chat, prompt, buttons);
        return true;
    }

    private Map<String, String> values(String payload) {
        var node = json.read(payload, tools.jackson.databind.JsonNode.class);
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : List.of("request", "name", "price", "grams")) if (node.has(key)) result.put(key, node.path(key).asText());
        return result;
    }
}
