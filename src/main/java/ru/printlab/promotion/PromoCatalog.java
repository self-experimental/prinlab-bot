package ru.printlab.promotion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.operator.OperatorAccess;
import ru.printlab.order.OrderDao;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.math.BigDecimal;

@Service
@Transactional(noRollbackFor = BusinessException.class)
public class PromoCatalog {
    private final PromoCodeDao dao;
    private final PromotionDao usage;
    private final OperatorAccess operators;
    private final OrderDao orders;
    private final Clock clock;

    public PromoCatalog(PromoCodeDao dao, PromotionDao usage, OperatorAccess operators, OrderDao orders, Clock clock) {
        this.dao = dao;
        this.usage = usage;
        this.operators = operators;
        this.orders = orders;
        this.clock = clock;
    }

    public PromoCode get(long actor, UUID id) {
        requireAdmin(actor);
        PromoCode code = dao.get(id);
        if (code == null) throw new BusinessException("Промокод не найден.");
        return code;
    }

    public List<PromoCode> page(long actor, boolean deleted, int page) {
        requireAdmin(actor);
        if (page < 0 || page > 10000) throw new BusinessException("Открой список промокодов заново.");
        return dao.page(deleted, page);
    }

    public PromoCode save(long actor, PromoCode input) {
        requireAdmin(actor);
        orders.lock();
        String code = input.code().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_]{1,20}")) throw new BusinessException("Код должен содержать от 1 до 20 латинских букв, цифр или знаков подчёркивания.");
        PromoCode duplicate = dao.byCode(code);
        if (duplicate != null && !duplicate.id().equals(input.id())) throw new BusinessException("Промокод с таким названием уже существует.");
        if (input.percent() == null || input.percent().signum() <= 0 || input.percent().compareTo(new BigDecimal("100")) > 0
                || input.percent().stripTrailingZeros().scale() > 2) throw new BusinessException("Скидка должна быть больше 0 и не больше 100%, до двух знаков после запятой.");
        if (input.type() == null || input.startsAt() == null || input.expiresAt() == null || !input.expiresAt().isAfter(input.startsAt()))
            throw new BusinessException("Дата окончания должна быть позже даты начала.");
        if (input.maxUsers() != null && (input.maxUsers() < 1 || input.maxUsers() > 1000000)) throw new BusinessException("Лимит пользователей должен быть от 1 до 1000000.");
        if ((input.type() == PromoType.USER_LIMIT || input.type() == PromoType.COMBINED) && input.maxUsers() == null)
            throw new BusinessException("Для этого вида промокода нужен лимит пользователей.");
        if (input.maxOrdersPerUser() < 1 || input.maxOrdersPerUser() > 1000) throw new BusinessException("Лимит заказов на пользователя должен быть от 1 до 1000.");
        if (input.type() == PromoType.FIRST_ORDER && input.maxOrdersPerUser() != 1) throw new BusinessException("Промокод первого заказа можно применить только один раз на человека.");
        if (input.failureReason() != null && input.failureReason().length() > 500) throw new BusinessException("Причина отказа должна быть до 500 символов.");
        UUID id = input.id();
        Instant created = clock.instant();
        PromoCode previous = null;
        if (id == null) id = UUID.randomUUID();
        else {
            previous = get(actor, id);
            created = previous.createdAt();
            if (!previous.code().equals(code) && usage.reservations(id) > 0)
                throw new BusinessException("Этот код уже использован или зарезервирован. Можно изменить условия или создать новый код.");
        }
        int usedUsers = 0;
        int usedOrders = 0;
        if (previous != null) { usedUsers = previous.usedUsers(); usedOrders = previous.usedOrders(); }
        PromoCode saved = new PromoCode(id, code, input.type(), input.percent(), created, input.startsAt(), input.expiresAt(),
                input.enabled(), input.deleted(), input.failureReason(), input.maxUsers(), input.maxOrdersPerUser(), usedUsers, usedOrders,
                input.campaignGroup(), input.prerequisiteCode(), input.prerequisitePaidUsers());
        dao.save(saved);
        return saved;
    }

    public void availability(long actor, UUID id, boolean deleted) {
        PromoCode p = get(actor, id);
        save(actor, new PromoCode(p.id(), p.code(), p.type(), p.percent(), p.createdAt(), p.startsAt(), p.expiresAt(), p.enabled(),
                deleted, p.failureReason(), p.maxUsers(), p.maxOrdersPerUser(), p.usedUsers(), p.usedOrders(), p.campaignGroup(), p.prerequisiteCode(), p.prerequisitePaidUsers()));
    }

    public void toggle(long actor, UUID id) {
        PromoCode p = get(actor, id);
        save(actor, new PromoCode(p.id(), p.code(), p.type(), p.percent(), p.createdAt(), p.startsAt(), p.expiresAt(), !p.enabled(),
                p.deleted(), p.failureReason(), p.maxUsers(), p.maxOrdersPerUser(), p.usedUsers(), p.usedOrders(), p.campaignGroup(), p.prerequisiteCode(), p.prerequisitePaidUsers()));
    }

    private void requireAdmin(long actor) {
        if (!operators.isAdmin(actor)) throw new BusinessException("Действие доступно только оператору.");
    }
}
