package ru.printlab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("printlab")
public record AppProperties(boolean demoMode, boolean botEnabled, String botToken, Set<Long> adminIds,
                            Path storageDir, boolean pricingConfirmed, CostRates rates,
                            PromotionPolicy promotion, Map<String, Material> materials,
                            String contact, String termsUrl, String privacyUrl, String paymentInstructions,
                            PrinterProfile printer, PromotionPolicy secondaryPromotion) {
    @ConstructorBinding
    public AppProperties {
        if (adminIds == null) {
            adminIds = Set.of();
        }
        if (printer == null) {
            printer = new PrinterProfile("Bambu Lab A1 mini", new java.math.BigDecimal("180"),
                    new java.math.BigDecimal("180"), new java.math.BigDecimal("180"),
                    new java.math.BigDecimal("0.4"), 1);
        }
    }

    public AppProperties(boolean demoMode, boolean botEnabled, String botToken, Set<Long> adminIds,
                         Path storageDir, boolean pricingConfirmed, CostRates rates,
                         PromotionPolicy promotion, Map<String, Material> materials,
                         String contact, String termsUrl, String privacyUrl, String paymentInstructions) {
        this(demoMode, botEnabled, botToken, adminIds, storageDir, pricingConfirmed, rates, promotion,
                materials, contact, termsUrl, privacyUrl, paymentInstructions, null, null);
    }
}
