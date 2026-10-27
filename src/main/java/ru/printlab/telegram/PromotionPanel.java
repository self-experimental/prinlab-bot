package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.promotion.*;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import static ru.printlab.telegram.Notifications.button;

@Component
public class PromotionPanel {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final PromoCatalog catalog;
    private final DialogDao dialogs;
    private final Notifications messages;
    private final Json json;
    private final Clock clock;

    public PromotionPanel(PromoCatalog catalog, DialogDao dialogs, Notifications messages, Json json, Clock clock) {
        this.catalog = catalog; this.dialogs = dialogs; this.messages = messages; this.json = json; this.clock = clock;
    }

    public void callback(long user, long chat, String data) {
        String[] p = data.split(":", 3);
        switch (p[1]) {
            case "list", "archive" -> {
                dialogs.clear(user, chat);
                boolean archived = p[1].equals("archive");
                int page = Integer.parseInt(p[2]);
                List<PromoCode> codes = catalog.page(user, archived, page);
                List<Map<String,String>> buttons = new ArrayList<>();
                for (int index = 0; index < Math.min(5, codes.size()); index++) {
                    PromoCode code = codes.get(index);
                    String label = code.code() + " · " + code.percent().stripTrailingZeros().toPlainString() + "%";
                    if (!code.enabled()) label += " · выключен";
                    buttons.add(button(label, "pc:open:" + code.id()));
                }
                if (page > 0) buttons.add(button("Предыдущая", "pc:" + p[1] + ":" + (page-1)));
                if (codes.size() > 5) buttons.add(button("Следующая", "pc:" + p[1] + ":" + (page+1)));
                buttons.add(button("Добавить промокод", "pc:new"));
                if (!archived) buttons.add(button("Удалённые промокоды", "pc:archive:0"));
                buttons.add(button("Назад", "op:menu"));
                messages.buttons(chat, "Промокоды", buttons);
            }
            case "new" -> {
                dialogs.save(user, chat, "PROMO_EDIT", "code", null, "{}");
                prompt(chat, "code", Map.of());
            }
            case "edit" -> {
                PromoCode code = catalog.get(user, UUID.fromString(p[2]));
                Map<String,String> values = new LinkedHashMap<>();
                values.put("id", code.id().toString());
                values.put("created", code.createdAt().toString());
                values.put("enabled", Boolean.toString(code.enabled()));
                if (code.campaignGroup() != null) values.put("group", code.campaignGroup());
                if (code.prerequisiteCode() != null) values.put("prerequisite", code.prerequisiteCode());
                values.put("needed", Integer.toString(code.prerequisitePaidUsers()));
                dialogs.save(user, chat, "PROMO_EDIT", "code", null, json.write(values));
                messages.text(chat, "Текущий код: " + code.code() + ". Укажите поля заново. Если код уже использован или зарезервирован, его название менять нельзя.");
                prompt(chat, "code", values);
            }
            case "type" -> accept(user, chat, required(user, chat, "type"), p[2]);
            case "today" -> {
                BotDialog d = required(user, chat, "starts");
                accept(user, chat, d, LocalDate.now(clock.withZone(ZONE)).format(DATE));
            }
            case "skip" -> {
                BotDialog d = required(user, chat, null);
                Map<String,String> values = values(d.payload());
                if (!d.step().equals("reason") && !(d.step().equals("users") && !requiresUsers(values)))
                    throw new BusinessException("Это поле нельзя пропустить.");
                accept(user, chat, d, "");
            }
            case "save" -> {
                BotDialog d = required(user, chat, "review");
                PromoCode saved = catalog.save(user, build(values(d.payload())));
                dialogs.clear(user, chat);
                messages.text(chat, "Промокод сохранён.");
                card(chat, saved);
            }
            case "open", "toggle", "delete", "deleteYes", "restore" -> {
                UUID id = UUID.fromString(p[2]);
                if (p[1].equals("delete")) {
                    messages.buttons(chat, "Удалить промокод из активного списка? Его можно восстановить в разделе удалённых промокодов.", List.of(
                            button("Удалить", "pc:deleteYes:" + id), button("Назад", "pc:open:" + id)));
                    return;
                }
                if (p[1].equals("toggle")) catalog.toggle(user, id);
                if (p[1].equals("deleteYes")) catalog.availability(user, id, true);
                if (p[1].equals("restore")) catalog.availability(user, id, false);
                card(chat, catalog.get(user, id));
            }
            default -> throw new BusinessException("Открой список промокодов заново.");
        }
    }

    public boolean input(long user, long chat, String text) {
        BotDialog d = dialogs.get(user, chat);
        if (d == null || !d.kind().equals("PROMO_EDIT")) return false;
        if (text.isBlank()) throw new BusinessException("Для настройки промокода нужно текстовое сообщение.");
        accept(user, chat, d, text);
        return true;
    }

    private void accept(long user, long chat, BotDialog d, String text) {
        Map<String,String> v = values(d.payload());
        String next;
        try {
            switch (d.step()) {
                case "code" -> {
                    text = text.trim().toUpperCase(Locale.ROOT);
                    if (!text.matches("[A-Z0-9_]{1,20}")) throw new BusinessException("Код: от 1 до 20 латинских букв, цифр или знаков подчёркивания.");
                    next = "type";
                }
                case "type" -> { PromoType.valueOf(text); next = "percent"; }
                case "percent" -> {
                    BigDecimal percent = new BigDecimal(text.replace(',', '.'));
                    if (percent.signum() <= 0 || percent.compareTo(new BigDecimal("100")) > 0 || percent.stripTrailingZeros().scale() > 2)
                        throw new BusinessException("Скидка должна быть больше 0 и не больше 100%, до двух знаков после запятой.");
                    text = percent.toPlainString(); next = "starts";
                }
                case "starts" -> { date(text, false); next = "expires"; }
                case "expires" -> {
                    if (!date(text, true).isAfter(date(v.get("starts"), false))) throw new BusinessException("Дата окончания должна быть позже начала.");
                    next = "users";
                    if (v.get("type").equals("DATE")) { v.put("users", ""); next = "orders"; }
                    if (v.get("type").equals("FIRST_ORDER")) { v.put("orders", "1"); }
                }
                case "users" -> {
                    if (text.isBlank() && requiresUsers(v)) throw new BusinessException("Для этого типа нужен лимит пользователей.");
                    if (!text.isBlank()) positive(text, 1000000);
                    next = "orders";
                    if (v.get("type").equals("FIRST_ORDER")) { v.put("orders", "1"); next = "reason"; }
                }
                case "orders" -> { positive(text, 1000); next = "reason"; }
                case "reason" -> {
                    if (text.length() > 500) throw new BusinessException("Причина отказа должна быть до 500 символов.");
                    next = "review";
                }
                default -> throw new BusinessException("Используйте кнопки под карточкой промокода.");
            }
        } catch (NumberFormatException e) {
            throw new BusinessException("Не получилось распознать число. Попробуйте ещё раз.");
        } catch (DateTimeParseException e) {
            throw new BusinessException("Укажите дату в формате ДД.ММ.ГГГГ, например 31.12.2026.");
        }
        v.put(d.step(), text);
        dialogs.save(user, chat, "PROMO_EDIT", next, null, json.write(v));
        prompt(chat, next, v);
    }

    private void prompt(long chat, String step, Map<String,String> values) {
        List<Map<String,String>> buttons = new ArrayList<>();
        String text = switch (step) {
            case "code" -> "Введите название промокода. До 20 латинских букв, цифр или знаков подчёркивания.";
            case "type" -> "Выберите вид применения промокода.";
            case "percent" -> "Введите скидку в процентах, больше 0 и не больше 100.";
            case "starts" -> "Введите дату начала: ДД.ММ.ГГГГ. Время считается по Москве.";
            case "expires" -> "Введите последний день действия: ДД.ММ.ГГГГ. Промокод будет действовать до конца этого дня по Москве.";
            case "users" -> "Сколько разных пользователей могут применить промокод?";
            case "orders" -> "Сколько заказов один пользователь может оформить с этим промокодом? От 1 до 1000.";
            case "reason" -> "Введите причину отказа при невыполнении условий или пропустите поле. Для ошибок дат бот использует стандартное объяснение.";
            case "review" -> summary(build(values)) + "\n\nВсё верно?";
            default -> throw new BusinessException("Откройте промокод заново.");
        };
        if (step.equals("type")) for (PromoType type : PromoType.values()) buttons.add(button(type.label(), "pc:type:" + type.name()));
        if (step.equals("starts")) buttons.add(button("Сегодня", "pc:today"));
        if (step.equals("reason") || (step.equals("users") && !requiresUsers(values))) buttons.add(button("Пропустить", "pc:skip"));
        if (step.equals("review")) buttons.add(button("Сохранить", "pc:save"));
        buttons.add(button("Отмена", "pc:list:0"));
        messages.buttons(chat, text, buttons);
    }

    private void card(long chat, PromoCode p) {
        List<Map<String,String>> buttons = new ArrayList<>();
        if (p.deleted()) buttons.add(button("Восстановить", "pc:restore:" + p.id()));
        else {
            buttons.add(button("Изменить", "pc:edit:" + p.id()));
            String enabled = "Выключить";
            if (!p.enabled()) enabled = "Включить";
            buttons.add(button(enabled, "pc:toggle:" + p.id()));
            buttons.add(button("Удалить", "pc:delete:" + p.id()));
        }
        buttons.add(button("Назад", "pc:list:0"));
        messages.buttons(chat, summary(p), buttons);
    }

    private String summary(PromoCode p) {
        String users = "без ограничения";
        if (p.maxUsers() != null) users = p.maxUsers().toString();
        String text = "Промокод: " + p.code() + "\nВид: " + p.type().label() + "\nСкидка: " + p.percent().stripTrailingZeros().toPlainString().replace('.', ',') + "%"
                + "\nСоздан: " + p.createdAt().atZone(ZONE).toLocalDate().format(DATE)
                + "\nНачало: " + p.startsAt().atZone(ZONE).toLocalDate().format(DATE)
                + "\nОкончание: " + p.expiresAt().atZone(ZONE).minusNanos(1).toLocalDate().format(DATE)
                + "\nЛимит пользователей: " + users + "\nЗаказов на пользователя: " + p.maxOrdersPerUser()
                + "\nПрименили пользователей: " + p.usedUsers() + "\nОплаченных применений: " + p.usedOrders();
        if (p.failureReason() != null && !p.failureReason().isBlank()) text += "\nПричина отказа: " + p.failureReason();
        if (p.prerequisiteCode() != null) text += "\nОткрывается после " + p.prerequisitePaidUsers() + " оплат с " + p.prerequisiteCode();
        return text;
    }

    private PromoCode build(Map<String,String> v) {
        UUID id = null;
        if (v.containsKey("id")) id = UUID.fromString(v.get("id"));
        Integer users = null;
        if (v.containsKey("users") && !v.get("users").isBlank()) users = Integer.valueOf(v.get("users"));
        boolean enabled = true;
        if (v.containsKey("enabled")) enabled = Boolean.parseBoolean(v.get("enabled"));
        Instant created = clock.instant();
        if (v.containsKey("created")) created = Instant.parse(v.get("created"));
        return new PromoCode(id, v.get("code"), PromoType.valueOf(v.get("type")), new BigDecimal(v.get("percent")), created,
                date(v.get("starts"), false), date(v.get("expires"), true), enabled, false, v.get("reason"), users,
                Integer.parseInt(v.get("orders")), 0, 0, v.get("group"), v.get("prerequisite"), Integer.parseInt(v.getOrDefault("needed", "0")));
    }

    private Instant date(String text, boolean endOfDay) {
        LocalDate date = LocalDate.parse(text, DATE);
        if (endOfDay) date = date.plusDays(1);
        return date.atStartOfDay(ZONE).toInstant();
    }

    private boolean requiresUsers(Map<String,String> values) {
        return values.get("type").equals("USER_LIMIT") || values.get("type").equals("COMBINED");
    }

    private int positive(String value, int maximum) {
        int number = Integer.parseInt(value);
        if (number < 1 || number > maximum) throw new BusinessException("Введите целое число от 1 до " + maximum + ".");
        return number;
    }

    private Map<String,String> values(String payload) {
        JsonNode node = json.read(payload, JsonNode.class);
        Map<String,String> values = new LinkedHashMap<>();
        for (String key : List.of("id", "created", "enabled", "group", "prerequisite", "needed", "code", "type", "percent", "starts", "expires", "users", "orders", "reason"))
            if (node.has(key)) values.put(key, node.path(key).asText());
        return values;
    }

    private BotDialog required(long user, long chat, String step) {
        BotDialog d = dialogs.get(user, chat);
        if (d == null || !d.kind().equals("PROMO_EDIT") || (step != null && !d.step().equals(step)))
            throw new BusinessException("Откройте редактор промокода заново.");
        return d;
    }
}
