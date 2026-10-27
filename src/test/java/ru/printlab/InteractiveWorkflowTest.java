package ru.printlab;

import org.junit.jupiter.api.Test;
import ru.printlab.order.*;
import ru.printlab.telegram.BotText;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InteractiveWorkflowTest extends BotTestSupport {
    @org.springframework.beans.factory.annotation.Autowired ru.printlab.slicing.SlicingTransactions slicing;
    @org.springframework.beans.factory.annotation.Autowired ru.printlab.slicing.SlicingDao jobs;

    @Test
    void failedSlicingOffersATextConversationAndDoesNotShowAnOldQuote() {
        PrintOrder order = draft(222);
        service.submit(222, order.id());
        jobs.enqueue(order.id(), clock.instant());
        slicing.fail(slicing.claim(), "TEST: invalid geometry");
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(orders.get(order.id()).quoteId()).isNull();
        assertThat(messages(222)).contains("Не удалось завершить расчёт");
        assertThat(jdbc.sql("SELECT payload FROM outbox").query(String.class).list())
                .anyMatch(p -> p.contains("Написать сообщение") && p.contains("question:" + order.id()));
        click(222, "question:" + order.id());
        send(222, "Помогите проверить модель");
        assertThat(messages(-12345)).contains("Помогите проверить модель");
    }

    @Test
    void aQuoteAwaitingFinancialApprovalStillShowsPricesAndCollectsDelivery() {
        PrintOrder order = draft(222);
        assertThat(service.applyPromotion(222, order.id(), "FIRST50").applied()).isTrue();
        service.submit(222, order.id());
        service.estimate(111, order.number(), Fixtures.estimate());
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(messages(222)).contains("Расчёт заказа", "Печать:", "Скидка:", "Как отправить заказ СДЭК?");
        click(222, "ship:" + order.id() + ":COURIER");
        send(222, "Тестовый Иван Иванович");
        send(222, "+79991234567");
        send(222, "Тест: Москва, Тестовая 1, 101000");
        assertThat(delivery.get(order.id()).address()).contains("Тестовая");
        click(111, -12345, "op:delivery:" + order.id());
        group(111, Map.of("text", "350,50"));
        PrintOrder priced = orders.get(order.id());
        assertThat(messages(222)).contains("Доставка до", "Итого:", "350,50 ₽");
        click(222, "accept:" + priced.quoteId());
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        service.approve(111, order.number(), "Проверка акции");
        click(222, "accept:" + priced.quoteId());
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @Test
    void uploadModesAreStrictAndTheCustomerConversationAcceptsOnlyText() {
        send(222,"/start");click(222,"consent:print");
        PrintOrder stl=orders.draft(222);
        message(222,222,Map.of("photo",List.of(Map.of("file_id","photo","width",100,"height",100))));
        message(222,222,document("wrong.PNG","picture"));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("modeling_photos")).isZero();
        assertThat(intake.get(222).mode()).isEqualTo("STL");
        assertThat(messages(222)).contains("Выбрана печать по STL");
        send(222,BotText.get("button.model"));
        PrintOrder photo=orders.list(222,false).getFirst();
        message(222,222,document("wrong.STL","stl-file"));
        assertThat(orders.get(photo.id()).status()).isEqualTo(OrderStatus.MODELING);
        assertThat(count("modeling_photos")).isZero();
        assertThat(intake.get(222).mode()).isEqualTo("PHOTO");
        for(String extension:List.of("PNG","JPG","jpeg","HEIC","HEIF","WEBP","gif","bmp","tiff","avif","dng"))
            message(222,222,document("image."+extension,"picture-"+extension));
        assertThat(count("modeling_photos")).isEqualTo(11);
        click(222,"question:"+photo.id());
        long before=count("modeling_photos");
        message(222,222,document("chat.PNG","chat-photo"));
        message(222,222,document("chat.stl","stl-file"));
        message(222,222,Map.of("photo",List.of(Map.of("file_id","chat-photo","width",100,"height",100))));
        assertThat(count("modeling_photos")).isEqualTo(before);
        assertThat(dialogs.get(222,222).kind()).isEqualTo("CHAT");
        assertThat(messages(222)).contains("Напиши текстовое сообщение по заказу","нужно текстовое сообщение");
        send(222,"Текст для команды");
        assertThat(messages(-12345)).contains("Текст для команды");
        click(222,"nav:home");
        message(222,222,document("outside.jpg","outside-photo"));
        assertThat(count("modeling_photos")).isEqualTo(before);
        click(222,"photos:"+photo.id());
        message(222,222,document("additional.HEIC","more"));
        assertThat(count("modeling_photos")).isEqualTo(before+1);
        assertThat(orders.get(stl.id()).modelId()).isNull();
    }

    @Test
    void operatorsCanSendPhotosButTheReadyModelWorkflowAcceptsOnlyStl() {
        send(222,"/start");click(222,"consent:model");
        PrintOrder order=orders.list(222,false).getFirst();
        message(222,222,document("reference.PNG","reference"));
        click(111,-12345,"op:reply:"+order.id());
        group(111,Map.of("photo",List.of(Map.of("file_id","operator-photo","width",100,"height",100))));
        assertThat(files(222,"sendPhoto")).isEqualTo(1);
        click(111,-12345,"op:photo:"+order.id());
        group(111,document("operator.HEIC","operator-heic"));
        assertThat(files(222,"sendDocument")).isEqualTo(1);
        click(111,-12345,"op:photo:"+order.id());
        group(111,document("wrong.stl","stl-file"));
        assertThat(files(222,"sendDocument")).isEqualTo(1);
        assertThat(orders.get(order.id()).modelId()).isNull();
        click(111,-12345,"op:stl:"+order.id());
        group(111,document("wrong.PNG","wrong"));
        group(111,Map.of("photo",List.of(Map.of("file_id","wrong","width",100,"height",100))));
        assertThat(orders.get(order.id()).modelId()).isNull();
        group(111,document("ready.STL","stl-file"));
        assertThat(orders.get(order.id()).modelId()).isNotNull();
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.DRAFT);
        click(111,-12345,"op:photo:"+order.id());
        for(int n=1;n<=2;n++)group(111,Map.of("media_group_id","album-1","photo",List.of(Map.of("file_id","album-"+n,"width",100,"height",100))));
        assertThat(files(222,"sendPhoto")).isEqualTo(3);
        group(111,Map.of("text","Не отправлять случайный текст после альбома"));
        assertThat(messages(222)).doesNotContain("Не отправлять случайный текст после альбома");
    }

    @Test
    void startingANewOrderRequiresConfirmationAndOnlyDiscardsItsOwnCurrentDraft() {
        PrintOrder first=draft(222);
        PrintOrder another=service.newDraft(222,true);
        click(222,"show:"+first.id());
        send(222,"Новый заказ");
        assertThat(orders.get(first.id()).deleted()).isFalse();
        assertThat(dialogs.get(222,222).orderId()).isEqualTo(first.id());
        click(222,"show:"+first.id());
        click(222,"newConfirm:"+first.id());
        assertThat(orders.get(first.id()).deleted()).isFalse();
        send(222,"Новый заказ");
        click(222,"newConfirm:"+first.id());
        assertThat(orders.get(first.id()).deleted()).isTrue();
        assertThat(orders.get(another.id()).deleted()).isFalse();
        click(222,"nav:print");
        PrintOrder fresh=orders.draft(222);
        assertThat(fresh.id()).isNotEqualTo(first.id()).isNotEqualTo(another.id());
        click(222,"edit:"+first.id());
        assertThat(orders.get(first.id()).deleted()).isTrue();
        click(111,-12345,"op:restore:"+first.id());
        assertThat(orders.get(first.id()).deleted()).isFalse();
        assertThat(orders.get(first.id()).modelId()).isEqualTo(first.modelId());
        click(111,-12345,"op:cancel:"+fresh.id());
        assertThat(orders.get(fresh.id()).status()).isEqualTo(OrderStatus.DRAFT);
        click(111,-12345,"op:cancelYes:"+fresh.id());
        assertThat(orders.get(fresh.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        click(111,-12345,"op:deleteYes:"+fresh.id());
        assertThat(orders.list(222,false)).noneMatch(o->o.id().equals(fresh.id()));
        click(111,-12345,"op:restore:"+fresh.id());
        assertThat(orders.list(222,false)).anyMatch(o->o.id().equals(fresh.id()));
    }

    @Test
    void deletionRetainsPaidAccountingAndCanBeUndone() {
        PrintOrder order=draft(222);
        pay(222,order);
        service.delete(111,order.id());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.PAID);
        service.restore(111,order.id());
        assertThat(orders.get(order.id()).paidAt()).isNotNull();
        assertThat(count("payments")).isEqualTo(1);
    }

    private Map<String,Object> document(String name,String id) {
        return Map.of("document",Map.of("file_name",name,"file_id",id,"file_size",100));
    }
}
