package ru.printlab.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.printlab.common.Json;
import ru.printlab.order.OrderDao;
import ru.printlab.order.OrderService;
import ru.printlab.order.OrderStatus;
import ru.printlab.order.PrintOrder;
import ru.printlab.order.Quote;
import ru.printlab.pricing.Estimate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

@Component
@Profile("demo")
public class DemoRunner implements ApplicationRunner {
    private final OrderDao orders;
    private final OrderService service;
    private final Clock clock;
    private final Json json;
    private final ConfigurableApplicationContext context;

    public DemoRunner(OrderDao orders, OrderService service, Clock clock, Json json, ConfigurableApplicationContext context) {
        this.orders = orders;
        this.service = service;
        this.clock = clock;
        this.json = json;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        orders.register(111, "Демо-оператор", null, clock.instant());
        orders.register(222, "Демо-клиент", "demo-card", clock.instant());
        orders.consent(222, clock.instant());
        String stl = """
                solid demo
                facet normal 0 0 1
                outer loop
                vertex 0 0 0
                vertex 10 0 0
                vertex 0 10 0
                endloop
                endfacet
                endsolid demo
                """;
        PrintOrder order = service.attach(222, "demo-file-id", "demo.stl", stl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        service.configure(222, order.id(), "material", "pla-black");
        service.configure(222, order.id(), "promo", "FIRST50");
        service.submit(222, order.id());
        Estimate example = new Estimate(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("210"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Quote quote = service.estimate(111, order.number(), example);
        if (quote.snapshot().economics().needsReview()) {
            service.approve(111, order.number(), "Демонстрация финансового согласования, не реальный заказ");
        }
        service.accept(222, quote.id());
        service.paid(111, order.number(), quote.snapshot().publicPrice().total(), "DEMO-PAYMENT");
        service.production(111, order.number(), OrderStatus.PRINTING);
        service.production(111, order.number(), OrderStatus.QUALITY_CHECK);
        service.production(111, order.number(), OrderStatus.READY);
        service.production(111, order.number(), OrderStatus.COMPLETED);
        Path output = Path.of("target/demo-result.json").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.write(Map.of("mode", "DEMO: example consumption; no real slicing or payment",
                "order", orders.get(order.id()), "quote", quote)));
        System.out.println("DEMO COMPLETE: " + output);
        System.out.println(service.internalSummary(order, quote));
        context.close();
    }
}
