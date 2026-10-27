package ru.printlab;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.printlab.common.Json;
import ru.printlab.order.*;
import ru.printlab.promotion.*;
import ru.printlab.telegram.*;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
@TestPropertySource(properties="printlab.operations.admin-usernames=oilbanana,muslimitsuhide")
abstract class BotTestSupport {
    @Autowired OrderService service;
    @Autowired OrderDao orders;
    @Autowired DeliveryDao delivery;
    @Autowired UpdateProcessor processor;
    @Autowired PromoCatalog catalog;
    @Autowired PromoCodeDao codes;
    @Autowired PromotionService promotions;
    @Autowired PromotionDao usage;
    @Autowired DialogDao dialogs;
    @Autowired IntakeDao intake;
    @Autowired JdbcClient jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired Json json;
    @Autowired MutableClock clock;
    @MockitoBean TelegramGateway telegram;
    private long update;

    @BeforeEach
    void reset() {
        for (String table : List.of("customer_intake", "order_messages", "bot_dialogs", "modeling_receipts", "delivery_details", "outbox",
                "order_actuals", "order_events", "payments", "promotion_reservations", "slicing_jobs", "quotes", "modeling_photos", "orders", "model_files", "customers"))
            jdbc.sql("DELETE FROM " + table).update();
        new ResourceDatabasePopulator(new ClassPathResource("test-promotions.sql")).execute(dataSource);
        jdbc.sql("UPDATE bot_cursor SET next_update = 0").update();
        jdbc.sql("DELETE FROM operator_accounts").update();
        jdbc.sql("UPDATE operator_chat SET telegram_chat_id = -12345").update();
        clock.set(Instant.parse("2026-10-28T12:00:00Z"));
        update=1;
        send(111,"/start");
        when(telegram.download("stl-file")).thenReturn(Fixtures.stl());
    }

    void send(long user,String text) { message(user,user,Map.of("text",text)); }
    void group(long user,Map<String,Object> fields) { message(user,-12345,fields); }
    void message(long user,long chat,Map<String,Object> fields) {
        Map<String,Object> body=new LinkedHashMap<>(fields);
        String type="private";
        if(chat<0)type="supergroup";
        body.put("chat",Map.of("id",chat,"type",type));
        body.put("from",Map.of("id",user,"first_name","Тест"));
        processor.process(json.read(json.write(Map.of("update_id",update++,"message",body)),JsonNode.class));
    }
    void click(long user,String data) { click(user,user,data); }
    void click(long user,long chat,String data) {
        String type="private";
        if(chat<0)type="supergroup";
        Map<String,Object> query=Map.of("id","callback-"+update,"data",data,"from",Map.of("id",user),
                "message",Map.of("chat",Map.of("id",chat,"type",type)));
        processor.process(json.read(json.write(Map.of("update_id",update++,"callback_query",query)),JsonNode.class));
    }
    PrintOrder draft(long user) {
        send(user,"/start");click(user,"consent:print");
        PrintOrder d=service.attach(user,"stl-file","part.stl",Fixtures.stl());
        return service.configure(user,d.id(),"color","00000000-0000-4000-8000-000000000001");
    }
    void pay(long user,PrintOrder draft) {
        service.submit(user,draft.id());
        Quote quote=service.estimate(111,draft.number(),Fixtures.estimate());
        if(orders.get(draft.id()).status()==OrderStatus.REVIEW_REQUIRED)service.approve(111,draft.number(),"Тестовое согласование");
        service.delivery(user,draft.id(),"PICKUP","Тестовый Получатель","+70000000000","Тестовый ПВЗ СДЭК");
        quote=service.priceDelivery(111,draft.id(),quote.id(),BigDecimal.ZERO);
        if(orders.get(draft.id()).status()==OrderStatus.REVIEW_REQUIRED)service.approve(111,draft.number(),"Тестовое согласование доставки");
        service.accept(user,quote.id());
        service.paid(111,draft.number(),quote.snapshot().publicPrice().total(),"TEST-"+draft.number());
    }
    long count(String table) { return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single(); }
    String messages(long chat) {
        StringBuilder text=new StringBuilder();
        for(String payload:jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendMessage' ORDER BY id").query(String.class).list()){
            JsonNode p=json.read(payload,JsonNode.class);
            if(p.path("chat_id").asLong()==chat)text.append(p.path("text").asText()).append('\n');
        }
        return text.toString();
    }
    long files(long chat,String method) {
        long count=0;
        for(String payload:jdbc.sql("SELECT payload FROM outbox WHERE method = ?").param(method).query(String.class).list())
            if(json.read(payload,JsonNode.class).path("chat_id").asLong()==chat)count++;
        return count;
    }
}
