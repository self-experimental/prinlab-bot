package ru.printlab.operator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties("printlab.operations")
public record OperatorProperties(Set<String> adminUsernames) {
    public OperatorProperties {
        Set<String> normalized = new HashSet<>();
        if (adminUsernames != null) {
            for (String username : adminUsernames) {
                String name = username.replaceFirst("^@", "").toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_]{5,32}")) {
                    throw new IllegalStateException("Неверный username оператора");
                }
                normalized.add(name);
            }
        }
        adminUsernames = Set.copyOf(normalized);
    }
}
