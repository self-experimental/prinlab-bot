package ru.printlab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.printlab.common.Money;
import ru.printlab.operator.OperatorProperties;

import java.math.BigDecimal;
import java.time.Clock;

@Configuration
public class RuntimeConfig {
    public RuntimeConfig(AppProperties properties, OperatorProperties operators) {
        CostRates rates = properties.rates();
        Money.nonNegative(rates.electricityPerKwh(), "Электроэнергия");
        Money.nonNegative(rates.printerKw(), "Мощность");
        Money.nonNegative(rates.depreciationPerHour(), "Амортизация");
        Money.nonNegative(rates.printerPurchaseCost(), "Стоимость принтера");
        Money.nonNegative(rates.printerLifetimeHours(), "Ресурс принтера");
        Money.nonNegative(rates.launchFee(), "Цена запуска");
        if (rates.printerPurchaseCost().signum() > 0 && rates.printerLifetimeHours().signum() == 0) {
            throw new IllegalStateException("Для стоимости принтера задайте ресурс в машинных часах");
        }
        PrinterProfile printer = properties.printer();
        if (printer.maxColors() != 1 || !printer.model().equals("Bambu Lab A1 mini")
                || printer.widthMm().compareTo(new BigDecimal("180")) != 0
                || printer.depthMm().compareTo(new BigDecimal("180")) != 0
                || printer.heightMm().compareTo(new BigDecimal("180")) != 0) {
            throw new IllegalStateException("MVP настроен на A1 mini 180×180×180 мм и один цвет");
        }
        Money.nonNegative(rates.maintenancePerHour(), "Обслуживание");
        Money.nonNegative(rates.laborPerHour(), "Работа");
        Money.nonNegative(rates.packaging(), "Упаковка");
        Money.nonNegative(rates.acquisitionPerNewCustomer(), "Привлечение");
        Money.nonNegative(rates.paymentFixed(), "Фиксированная комиссия");
        Money.nonNegative(rates.minimumPrintPrice(), "Минимальная цена");
        Money.nonNegative(rates.minimumContribution(), "Минимальный доход");
        Money.nonNegative(rates.minimumProfitMarkup(), "Минимальная доходность к затратам");
        fraction(rates.paymentRate());
        fraction(rates.taxRate());
        fraction(rates.targetMargin());
        fraction(rates.failureProbability());
        BigDecimal receiptRate = rates.paymentRate().add(rates.taxRate());
        BigDecimal divisor = BigDecimal.ONE.subtract(receiptRate).subtract(rates.targetMargin());
        if (rates.targetProfitMarkup() != null) {
            Money.nonNegative(rates.targetProfitMarkup(), "Целевая доходность к затратам");
            if (rates.targetProfitMarkup().compareTo(rates.minimumProfitMarkup()) < 0) {
                throw new IllegalStateException("Целевая доходность не может быть ниже минимальной");
            }
            divisor = BigDecimal.ONE.subtract(receiptRate.multiply(BigDecimal.ONE.add(rates.targetProfitMarkup())));
        }
        if (divisor.signum() <= 0 || rates.quoteHours() < 1 || rates.quoteHours() > 168
                || rates.version() == null || rates.version().isBlank()) {
            throw new IllegalStateException("Некорректные ставки, версия тарифа или срок предложения");
        }
        if (properties.materials().isEmpty()) {
            throw new IllegalStateException("Настройте хотя бы один материал");
        }
        for (String key : properties.materials().keySet()) {
            if (!key.matches("[a-z0-9-]{1,18}")) {
                throw new IllegalStateException("Ключ материала: до 18 латинских строчных букв, цифр и дефисов");
            }
        }
        for (Material material : properties.materials().values()) {
            if (Money.nonNegative(material.costPerKg(), "Цена материала").signum() == 0) {
                throw new IllegalStateException("Цена материала должна быть положительной");
            }
        }
        promotion(properties.promotion());
        if (properties.secondaryPromotion() != null) {
            promotion(properties.secondaryPromotion());
            if (properties.secondaryPromotion().code().equals(properties.promotion().code())) {
                throw new IllegalStateException("Промокоды двух этапов должны различаться");
            }
        }
        if (properties.botEnabled()) {
            if (properties.demoMode() || !properties.pricingConfirmed() || properties.botToken().isBlank()
                    || (properties.adminIds().isEmpty() && operators.adminUsernames().isEmpty()) || properties.adminIds().contains(0L)
                    || properties.contact().isBlank() || properties.paymentInstructions().isBlank()) {
                throw new IllegalStateException("Для бота настройте токен, операторов, тарифы, контакты и оплату; demo должен быть выключен");
            }
            for (String url : java.util.List.of(properties.termsUrl(), properties.privacyUrl())) {
                if (!url.isBlank() && !url.startsWith("https://")) {
                    throw new IllegalStateException("Ссылки на условия и обработку данных должны использовать HTTPS");
                }
            }
        }
    }

    private static void fraction(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException("Ставка должна быть долей от 0 включительно до 1 исключительно");
        }
    }

    private static void promotion(PromotionPolicy promo) {
        fraction(promo.rate());
        if (promo.maxDiscount() != null) {
            Money.nonNegative(promo.maxDiscount(), "Лимит скидки");
        }
        if (promo.budget() != null) {
            Money.nonNegative(promo.budget(), "Бюджет акции");
        }
        if (promo.maxPaidOrders() < 1 || promo.endsAt() == null || !promo.code().matches("[A-Z0-9_]{1,20}")) {
            throw new IllegalStateException("Некорректные условия акции");
        }
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
