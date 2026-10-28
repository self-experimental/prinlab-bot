package ru.printlab.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.operator.OperatorAccess;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(noRollbackFor = BusinessException.class)
public class ColorService {
    private final ColorDao dao;
    private final OperatorAccess operators;

    public ColorService(ColorDao dao, OperatorAccess operators) {
        this.dao = dao;
        this.operators = operators;
    }

    public List<FilamentColor> page(boolean customer, int page) {
        if (page < 0 || page > 10000) {
            throw new BusinessException("Эта страница уже неактуальна. Открой каталог заново.");
        }
        return dao.page(customer, page);
    }

    public FilamentColor get(UUID id) {
        FilamentColor color = historical(id);
        if (color.deleted()) {
            throw new BusinessException("Цвет больше не доступен в каталоге. Выбери другой.");
        }
        return color;
    }

    public FilamentColor historical(UUID id) {
        FilamentColor color = dao.get(id);
        if (color == null) {
            throw new BusinessException("Цвет больше не доступен в каталоге. Выбери другой.");
        }
        return color;
    }

    public FilamentColor available(UUID id) {
        FilamentColor color = get(id);
        if (!color.available()) {
            throw new BusinessException("Этот цвет сейчас недоступен. Выбери другой.");
        }
        return color;
    }

    public FilamentColor save(long admin, FilamentColor input) {
        requireAdmin(admin);
        if (input.name() == null || input.name().isBlank() || input.name().length() > 80) {
            throw new BusinessException("Название цвета должно содержать от 1 до 80 символов.");
        }
        bounded(input.surface(), 40);
        bounded(input.manufacturer(), 120);
        bounded(input.paletteNumber(), 80);
        bounded(input.bambuArticle(), 160);
        if (input.spoolPrice() == null || input.spoolPrice().signum() <= 0 || input.spoolPrice().scale() > 2
                || input.spoolPrice().compareTo(new java.math.BigDecimal("1000000")) > 0)
            throw new BusinessException("Укажите цену катушки больше 0 и не больше 1000000 ₽, до двух знаков после запятой.");
        if (input.spoolWeightGrams() < 1 || input.spoolWeightGrams() > 100000)
            throw new BusinessException("Масса пластика в катушке должна быть от 1 до 100000 г.");
        UUID id = input.id();
        if (id == null) {
            id = UUID.randomUUID();
        } else {
            get(id);
        }
        FilamentColor color = new FilamentColor(id, input.name().trim(), input.surface(), input.manufacturer(),
                input.paletteNumber(), input.bambuArticle(), input.available(), false, input.spoolPrice(), input.spoolWeightGrams());
        dao.save(color);
        return color;
    }

    public void toggle(long admin, UUID id) {
        requireAdmin(admin);
        FilamentColor color = get(id);
        dao.save(new FilamentColor(id, color.name(), color.surface(), color.manufacturer(), color.paletteNumber(),
                color.bambuArticle(), !color.available(), false, color.spoolPrice(), color.spoolWeightGrams()));
    }

    public void delete(long admin, UUID id) {
        requireAdmin(admin);
        FilamentColor color = get(id);
        dao.save(new FilamentColor(id, color.name(), color.surface(), color.manufacturer(), color.paletteNumber(),
                color.bambuArticle(), false, true, color.spoolPrice(), color.spoolWeightGrams()));
    }

    private void requireAdmin(long user) {
        if (!operators.isAdmin(user)) {
            throw new BusinessException("Команда доступна только оператору.");
        }
    }

    private void bounded(String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new BusinessException("В этом поле можно указать до " + maximum + " символов.");
        }
    }
}
