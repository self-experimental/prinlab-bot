package ru.printlab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import ru.printlab.common.BusinessException;
import ru.printlab.catalog.*;
import ru.printlab.storage.StlValidator;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import ru.printlab.order.*;
import static org.assertj.core.api.Assertions.*;

class AdversarialWorkflowTest extends BotTestSupport {
 @Test void repeatedPromoApplyDoesNotConsumeQuotaOrDuplicateDiscount() {
  PrintOrder order=draft(222);
  for(int i=0;i<10;i++) {
   click(222,"input:"+order.id()+":promo");send(222,"FIRST50");
   click(222,"promoapply:"+order.id());click(222,"promoapply:"+order.id());
  }
  assertThat(orders.get(order.id()).promoCode()).isEqualTo("FIRST50");
  assertThat(count("promotion_reservations")).isZero();
  assertThat(count("payments")).isZero();
 }
 @Test void oldModelConsentRequiresNewOrderConfirmation() {
  PrintOrder order=draft(222);
  click(222,"consent:model");
  assertThat(count("orders")).isEqualTo(1);
  assertThat(orders.get(order.id()).deleted()).isFalse();
  assertThat(intake.get(222).mode()).isEqualTo("STL");
  assertThat(dialogs.get(222,222).kind()).isEqualTo("NEW_CONFIRM");
  click(222,"nav:model");send(222,"/model");
  assertThat(count("orders")).isEqualTo(1);
  click(222,"newConfirm:"+order.id());click(222,"nav:model");
  assertThat(count("orders")).isEqualTo(2);
  assertThat(orders.get(order.id()).deleted()).isTrue();
  assertThat(intake.get(222).mode()).isEqualTo("PHOTO");
  send(222,"/draft");
  assertThat(count("orders")).isEqualTo(2);
  assertThat(dialogs.get(222,222).kind()).isEqualTo("NEW_CONFIRM");
 }
 @Test void repeatedSubmitDoesNotCreateAnotherOrderOrQuote() {
  PrintOrder order=draft(222);
  for(int i=0;i<10;i++)click(222,"submit:"+order.id());
  assertThat(count("orders")).isEqualTo(1);
  assertThat(count("quotes")).isZero();
  assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.ESTIMATING);
 }

 @ParameterizedTest(name="layer={0}, valid={1}")
 @CsvSource({"0.07,false","0.08,true","0.080,true","0.28,true","0.29,false","0.125,false","0,false", "NaN,false","1e1000,false","1e-1000,false"})
 void layerBounds(String value,boolean valid){
  PrintOrder o=draft(222);
  if(valid)assertThat(service.configure(222,o.id(),"layer",value).layerHeightMm()).isEqualByComparingTo(value);
  else assertThatThrownBy(()->service.configure(222,o.id(),"layer",value)).isInstanceOf(BusinessException.class);
 }
 @ParameterizedTest(name="{0}={1}, valid={2}")
 @CsvSource({"infill,0,false","infill,1,true","infill,4,true","infill,5,true","infill,100,true","infill,101,false","infill,1.5,false","infill,2147483648,false","quantity,0,false","quantity,1,true","quantity,100,true","quantity,101,false","quantity,-1,false","quantity,1e2,false"})
 void integerBounds(String field,String value,boolean valid){
  PrintOrder o=draft(222);
  if(valid){PrintOrder result=service.configure(222,o.id(),field,value);if(field.equals("infill"))assertThat(result.infill()).isEqualTo(Integer.parseInt(value));else assertThat(result.quantity()).isEqualTo(Integer.parseInt(value));}
  else assertThatThrownBy(()->service.configure(222,o.id(),field,value)).isInstanceOf(BusinessException.class);
 }
 @Test void invalidCommandsDoNotCreateEmptyDrafts(){
  send(222,"/start");click(222,"agree");PrintOrder o=orders.draft(222);service.cancel(222,o.id());
  long before=count("orders");
  for(String command:List.of("/promo UNKNOWN","/promo FIRST30","/quantity -1","/quantity 3","/promo"))send(222,command);
  assertThat(count("orders")).isEqualTo(before);assertThat(orders.draft(222)).isNull();
 }
 @Test void parameterNavigationClearsOldInput(){
  PrintOrder o=draft(222);click(222,"input:"+o.id()+":quantity");
  click(222,"profile:"+o.id()+":FINE");
  assertThat(dialogs.get(222,222)).isNull();
  send(222,"30");assertThat(orders.get(o.id()).quantity()).isEqualTo(1);
  assertThat(orders.get(o.id()).infill()).isEqualTo(15);
 }
 @Test void first30RepeatedApplyAndDuplicatePaidReferencesDoNotExpandQuota(){
  for(long user:List.of(333L,444L)){PrintOrder o=draft(user);service.applyPromotion(user,o.id(),"FIRST50");pay(user,o);}
  PrintOrder order=draft(222);
  for(int i=0;i<10;i++){click(222,"input:"+order.id()+":promo");send(222," first30 ");click(222,"promoapply:"+order.id());click(222,"promoapply:"+order.id());}
  assertThat(count("promotion_reservations")).isEqualTo(2);
  pay(222,order);
  for(int i=0;i<10;i++)service.paid(111,order.number(),orders.quote(orders.get(order.id()).quoteId()).snapshot().publicPrice().total(),"TEST-"+order.number());
  assertThat(codes.byCode("FIRST30").usedUsers()).isEqualTo(1);assertThat(codes.byCode("FIRST30").usedOrders()).isEqualTo(1);
  PrintOrder another=draft(222);assertThat(service.applyPromotion(222,another.id(),"FIRST30").applied()).isFalse();
 }
 @Test void crossUserCallbacksAndForgedOperatorActionsCannotMutateAnOrder(){
  PrintOrder o=draft(222);send(333,"/start");
  for(String action:List.of("profile:"+o.id()+":FINE","quantity:"+o.id()+":100","cancelYes:"+o.id(),"customColor:"+o.id(),"show:"+o.id(),"op:deleteYes:"+o.id()))click(333,action);
  assertThat(orders.get(o.id()).profile()).isEqualTo("STANDARD");assertThat(orders.get(o.id()).quantity()).isEqualTo(1);assertThat(orders.get(o.id()).deleted()).isFalse();assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.DRAFT);
 }
 @Test void extensionAndSizeChecksDoNotDownloadOrSwitchToPhotoMode(){
  PrintOrder o=draft(222);long photos=count("modeling_photos");
  for(String name:List.of("wrong.png","wrong.JPG","wrong.stl.exe","wrong","wrong.HEIC"))message(222,222,Map.of("document",Map.of("file_name",name,"file_id","bad","file_size",20)));
  message(222,222,Map.of("document",Map.of("file_name","large.stl","file_id","large","file_size",StlValidator.MAX_BYTES+1)));
  verify(telegram,never()).download("bad");verify(telegram,never()).download("large");
  assertThat(count("modeling_photos")).isEqualTo(photos);assertThat(intake.get(222).mode()).isEqualTo("STL");
  assertThat(orders.get(o.id()).modelId()).isEqualTo(o.modelId());
 }
 @Autowired ColorService colors;
 @ParameterizedTest(name="spool={0}/{1}, valid={2}")
 @CsvSource({"0,1000,false","-1,1000,false","0.01,1,true","1000000,100000,true","1000000.01,1000,false","700,0,false","700,100001,false","700.001,1000,false"})
 void spoolBounds(String price,int grams,boolean valid){
  FilamentColor color=new FilamentColor(null,"QA_BOUND",null,null,null,null,true,false,new BigDecimal(price),grams);
  if(valid)assertThat(colors.save(111,color).spoolWeightGrams()).isEqualTo(grams);
  else assertThatThrownBy(()->colors.save(111,color)).isInstanceOf(BusinessException.class);
 }
 @ParameterizedTest(name="manualPrice={0}, valid={1}")
 @CsvSource({"0,true","0.01,true","-1,false","10000000,true","10000000.01,false","1.001,false","1e1000,false","1e-1000,false"})
 void editedPriceBounds(String price,boolean valid){
  PrintOrder o=draft(222);service.submit(222,o.id());Quote q=service.estimate(111,o.number(),Fixtures.estimate());
  if(valid)assertThat(service.editPrice(111,o.id(),q.id(),new BigDecimal(price),"Граничная проверка цены").snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo(price);
  else assertThatThrownBy(()->service.editPrice(111,o.id(),q.id(),new BigDecimal(price),"Граничная проверка цены")).isInstanceOf(BusinessException.class);
 }
 @ParameterizedTest(name="carrier={0}, valid={1}")
 @CsvSource({"0,true","0.01,true","-1,false","100000,true","100000.01,false","1.001,false"})
 void carrierBounds(String amount,boolean valid){
  PrintOrder o=draft(222);service.submit(222,o.id());Quote q=service.estimate(111,o.number(),Fixtures.estimate());
  service.delivery(222,o.id(),"PICKUP","Тестовый Иван Иванович","+79990000000","Тестовый ПВЗ");
  if(valid){Quote result=service.priceDelivery(111,o.id(),q.id(),new BigDecimal(amount));assertThat(result.snapshot().publicPrice().total()).isEqualByComparingTo(q.snapshot().publicPrice().total());assertThat(result.snapshot().costs().delivery()).isZero();}
  else assertThatThrownBy(()->service.priceDelivery(111,o.id(),q.id(),new BigDecimal(amount))).isInstanceOf(BusinessException.class);
 }
 @Test void customColorInputBoundaryAndStaleButtons(){
  PrintOrder o=draft(222);click(222,"customColor:"+o.id());
  send(222,"A");assertThat(count("color_requests")).isZero();send(222,"A".repeat(501));assertThat(count("color_requests")).isZero();
  send(222,"A".repeat(500));assertThat(count("color_requests")).isEqualTo(1);
  UUID id=jdbc.sql("SELECT id FROM color_requests").query(UUID.class).single();
  service.configure(222,o.id(),"color","00000000-0000-4000-8000-000000000001");
  click(111,-12345,"cc:accept:"+id);assertThat(dialogs.get(111,-12345)).isNull();
 }
 @Test void lowInfillConfirmationCannotBeReusedAfterLeavingTheStep(){
  PrintOrder o=draft(222);click(222,"input:"+o.id()+":infill");send(222,"1");
  assertThat(orders.get(o.id()).infill()).isEqualTo(15);click(222,"nav:home");click(222,"lowkeep:"+o.id());
  assertThat(orders.get(o.id()).infill()).isEqualTo(15);
 }

 @Test void hiddenPaidOrderCannotBeMovedByAdminCommand(){
  PrintOrder o=draft(222);pay(222,o);service.delete(111,o.id());
  send(111,"/status "+o.number()+" PRINTING");
  assertThat(orders.get(o.id()).deleted()).isTrue();assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.PAID);
  assertThat(messages(111)).contains("Сначала восстановите");
  service.restore(111,o.id());send(111,"/status "+o.number()+" PRINTING");
  assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.PRINTING);
 }
 @Test void byteLimitsAndStructuralChecks(){
  var validator=new StlValidator();
  byte[] prefix=("solid b\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid b\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  for(int size:List.of(15*1024*1024,15*1024*1024+1,StlValidator.MAX_BYTES)){
   byte[] body=new byte[size];Arrays.fill(body,(byte)' ');System.arraycopy(prefix,0,body,0,prefix.length);
   assertThatCode(()->validator.validate("edge.STL",body)).doesNotThrowAnyException();
  }
  assertThatThrownBy(()->validator.validate("empty.stl",new byte[0])).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->validator.validate("too-big.stl",new byte[StlValidator.MAX_BYTES+1])).isInstanceOf(BusinessException.class);
 }

 @Test void explicitReplyCannotBeSentToAnotherActiveClient(){
  PrintOrder first=draft(222);PrintOrder second=draft(333);
  jdbc.sql("INSERT INTO order_messages(chat_id,message_id,order_id) VALUES (-12345,7001,?)").param(first.id()).update();
  click(111,-12345,"op:reply:"+second.id());
  group(111,Map.of("text","QA_ONLY_FOR_FIRST_CLIENT","reply_to_message",Map.of("message_id",7001)));
  assertThat(messages(222)).doesNotContain("QA_ONLY_FOR_FIRST_CLIENT");
  assertThat(messages(333)).doesNotContain("QA_ONLY_FOR_FIRST_CLIENT");
  assertThat(messages(-12345)).contains("Ответ относится к другому заказу");
  click(111,-12345,"op:open:"+first.id());
  group(111,Map.of("text","QA_ONLY_FOR_FIRST_CLIENT","reply_to_message",Map.of("message_id",7001)));
  assertThat(messages(222)).contains("QA_ONLY_FOR_FIRST_CLIENT");
  assertThat(messages(333)).doesNotContain("QA_ONLY_FOR_FIRST_CLIENT");
 }
 @Test void customerExplicitReplyCannotBeRecordedAgainstAnotherActiveOrder(){
  PrintOrder first=draft(222);PrintOrder second=service.newDraft(222,true);
  jdbc.sql("INSERT INTO order_messages(chat_id,message_id,order_id) VALUES (222,7002,?)").param(first.id()).update();
  click(222,"question:"+second.id());
  message(222,222,Map.of("text","QA_REPLY_FIRST_ORDER","reply_to_message",Map.of("message_id",7002)));
  assertThat(jdbc.sql("SELECT count(*) FROM order_events WHERE action='CUSTOMER_MESSAGE' AND detail='QA_REPLY_FIRST_ORDER'").query(Long.class).single()).isZero();
  assertThat(messages(222)).contains("Ответ относится к другому заказу");
  click(222,"show:"+first.id());
  message(222,222,Map.of("text","QA_REPLY_FIRST_ORDER","reply_to_message",Map.of("message_id",7002)));
  UUID actual=jdbc.sql("SELECT order_id FROM order_events WHERE action='CUSTOMER_MESSAGE' AND detail='QA_REPLY_FIRST_ORDER'").query(UUID.class).single();
  assertThat(actual).isEqualTo(first.id());
 }
 @Test void paymentDialogFullHumanSequenceRejectsBadReferencesAndDuplicateActions(){
  PrintOrder o=awaitingPayment(222);
  assertThatThrownBy(()->service.refund(111,o.number(),"QA_REFUND_UNPAID")).isInstanceOf(BusinessException.class);
  click(111,-12345,"op:paid:"+o.id());
  assertThat(count("payments")).isZero();
  group(111,Map.of("text","QA_NOT_A_PAYMENT_YET"));
  assertThat(count("payments")).isZero();
  click(111,-12345,"op:payConfirm:"+o.id());
  for(String ref:List.of("AB","русский","with space","X".repeat(129))) {
   group(111,Map.of("text",ref));assertThat(count("payments")).isZero();
  }
  group(111,Map.of("text","QA_PAY_001"));
  assertThat(count("payments")).isEqualTo(1);assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.PAID);
  assertThatThrownBy(()->service.cancel(222,o.id())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.paid(111,o.number(),orders.quote(o.quoteId()).snapshot().publicPrice().total(),"QA_ANOTHER_REF")).isInstanceOf(BusinessException.class);
  click(111,-12345,"op:payConfirm:"+o.id());
  group(111,Map.of("text","QA_PAY_002"));
  assertThat(count("payments")).isEqualTo(1);
  for(String action:List.of("to_PRINTING","to_READY","to_SHIPPED","to_COMPLETED")) {
   click(111,-12345,"op:"+action+":"+o.id());click(111,-12345,"op:"+action+":"+o.id());
  }
  assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.COMPLETED);
  assertThat(messages(222)).contains("Оплата заказа", "в работе", "готов к отправке", "отправлен СДЭК", "выполнен");
  service.refund(111,o.number(),"QA_REFUND_001");service.refund(111,o.number(),"QA_REFUND_001");
  assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.REFUNDED);
  assertThat(count("payments")).isEqualTo(1);
 }
 @Test void backFromPaymentCancelsReferenceInputAndStaleConfirmationCannotCharge(){
  PrintOrder o=awaitingPayment(222);
  click(111,-12345,"op:paid:"+o.id());click(111,-12345,"op:payConfirm:"+o.id());
  click(111,-12345,"op:open:"+o.id());group(111,Map.of("text","QA_UNUSED_REF"));
  click(111,-12345,"op:payConfirm:"+o.id());
  assertThat(count("payments")).isZero();assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
 }
 @Test void selfPickupCannotBeShippedByCdek(){
  PrintOrder o=draft(222);service.submit(222,o.id());Quote q=service.estimate(111,o.number(),Fixtures.estimate());
  service.selfPickup(222,o.id(),"baumanskaya");service.approve(111,o.number(),"Самовывоз согласован");
  service.accept(222,q.id());service.paid(111,o.number(),q.snapshot().publicPrice().total(),"QA_PICKUP_PAY");
  service.production(111,o.number(),OrderStatus.PRINTING);service.production(111,o.number(),OrderStatus.READY);
  click(111,-12345,"op:status:"+o.id());
  assertThat(jdbc.sql("SELECT payload FROM outbox WHERE method='sendMessage'").query(String.class).list()).noneMatch(p->p.contains("op:to_SHIPPED:"+o.id()));
  click(111,-12345,"op:to_SHIPPED:"+o.id());send(111,"/status "+o.number()+" SHIPPED");
  assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.READY);
  assertThat(messages(222)).contains("готов к самовывозу").doesNotContain("отправлен СДЭК");
  assertThat(jdbc.sql("SELECT payload FROM outbox WHERE method='sendMessage'").query(String.class).list()).anyMatch(p->p.contains("pickupTime:"+o.id()));
  click(222,"pickupTime:"+o.id());send(222,"Заберу в субботу после 14:00");
  assertThat(messages(-12345)).contains("Заберу в субботу после 14:00");
  assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.READY);
  click(111,-12345,"op:open:"+o.id());
  var pickupCard=json.read(jdbc.sql("SELECT payload FROM outbox WHERE method='sendMessage' ORDER BY id DESC LIMIT 1").query(String.class).single(),tools.jackson.databind.JsonNode.class);
  assertThat(pickupCard.path("reply_markup").path("inline_keyboard").get(0).get(0).path("text").asText()).isEqualTo("Подтвердить выдачу");
  click(111,-12345,"op:to_COMPLETED:"+o.id());
  assertThat(orders.get(o.id()).status()).isEqualTo(OrderStatus.COMPLETED);
  click(222,"nav:home");click(222,"pickupTime:"+o.id());assertThat(dialogs.get(222,222)).isNull();
 }
 @Test void deliveryMenuClearsExistingNameInput(){
  PrintOrder o=draft(222);service.submit(222,o.id());service.estimate(111,o.number(),Fixtures.estimate());
  click(222,"ship:"+o.id()+":COURIER");click(222,"shipping:"+o.id());
  send(222,"Как выбрать самовывоз?");
  assertThat(dialogs.get(222,222)).isNull();
  assertThat(delivery.get(o.id())).isNull();
 }
 private PrintOrder awaitingPayment(long user){
  PrintOrder o=draft(user);service.submit(user,o.id());Quote q=service.estimate(111,o.number(),Fixtures.estimate());
  service.delivery(user,o.id(),"PICKUP","Тестовый Иван Иванович","+79990000000","Тестовый ПВЗ");
  q=service.priceDelivery(111,o.id(),q.id(),BigDecimal.ZERO);
  service.approve(111,o.number(),"Тестовые условия согласованы");
  service.accept(user,q.id());return orders.get(o.id());
 }
 @Test void catalogShowsFiveButtonsAndTheSixthColorOnTheNextPage(){
  PrintOrder o=draft(222);
  for(String id:List.of("00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002","00000000-0000-4000-8000-000000000003")){
   FilamentColor c=colors.get(UUID.fromString(id));
   colors.save(111,new FilamentColor(c.id(),c.name(),c.surface(),c.manufacturer(),c.paletteNumber(),c.bambuArticle(),false,false,c.spoolPrice(),c.spoolWeightGrams()));
  }
  for(int n=0;n<6;n++)colors.save(111,new FilamentColor(null,"QA_PAGE_"+n,null,null,null,null,true,false,new BigDecimal("700"),1000));
  click(222,"colors:"+o.id()+":0");
  var first=json.read(jdbc.sql("SELECT payload FROM outbox WHERE method='sendMessage' ORDER BY id DESC LIMIT 1").query(String.class).single(),tools.jackson.databind.JsonNode.class);
  int picks=0;for(var row:first.path("reply_markup").path("inline_keyboard"))for(var key:row)if(key.path("callback_data").asText().startsWith("pick:"))picks++;
  assertThat(picks).isEqualTo(5);assertThat(first.toString()).contains("Следующая").doesNotContain("Предыдущая");
  click(222,"colors:"+o.id()+":1");
  var second=json.read(jdbc.sql("SELECT payload FROM outbox WHERE method='sendMessage' ORDER BY id DESC LIMIT 1").query(String.class).single(),tools.jackson.databind.JsonNode.class);
  picks=0;for(var row:second.path("reply_markup").path("inline_keyboard"))for(var key:row)if(key.path("callback_data").asText().startsWith("pick:"))picks++;
  assertThat(picks).isEqualTo(1);assertThat(second.toString()).contains("Предыдущая").doesNotContain("Следующая");
  click(222,"colors:"+o.id()+":-1");assertThat(messages(222)).contains("страница уже неактуальна");
 }
 @Test void textAndPhotoCaptionLimitsKeepTheReplyStepOnFailure(){
  PrintOrder o=draft(222);click(222,"question:"+o.id());
  send(222,"X".repeat(2001));assertThat(jdbc.sql("SELECT count(*) FROM order_events WHERE action='CUSTOMER_MESSAGE'").query(Long.class).single()).isZero();
  send(222,"X".repeat(2000));assertThat(jdbc.sql("SELECT count(*) FROM order_events WHERE action='CUSTOMER_MESSAGE'").query(Long.class).single()).isEqualTo(1);
  click(111,-12345,"op:reply:"+o.id());group(111,Map.of("text","Y".repeat(2001)));
  assertThat(dialogs.get(111,-12345).kind()).isEqualTo("OP_REPLY");group(111,Map.of("text","Y".repeat(2000)));
  assertThat(dialogs.get(111,-12345)).isNull();
  click(111,-12345,"op:photo:"+o.id());
  var photo=List.of(Map.of("file_id","qa-photo","width",100,"height",100));
  group(111,Map.of("photo",photo,"caption","Z".repeat(901)));assertThat(files(222,"sendPhoto")).isZero();
  group(111,Map.of("photo",photo,"caption","Z".repeat(900)));assertThat(files(222,"sendPhoto")).isEqualTo(1);
 }
}
