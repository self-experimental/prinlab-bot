# Технические подтверждения проверки 30.10.2026

## Среда и метод

- Production: PrintLab 1.3.1, image revision `98d0f54f28d352f10067eb40004c2197e1421228`.
- Локальный HEAD `15b7aae189a37819da5a5894e2ffcdbe5c84beef`: после приложения только документация. Код приложения в этом прогоне не менялся.
- Нативный Telegram: клиентский PrintLab и группа Printlab Orders; текст, поиск чатов и загрузка файлов работают. Inline-click возвращает AXError.failure.
- Часть production callbacks вызвана временным Java-клиентом штатного BotCommands, с разрешением только на QA-заказы/QA-каталог/QA-промокоды. Он не получает Telegram updates и не запускает расписание. Вызов callback не считается ручным кликом.
- Платёжные статусы, возвраты, чужие клиенты, конкуренция и исчерпание квот проверены в отдельной H2/PostgreSQL; TelegramGateway там замокан. Проверяются изменения БД и исходящие уведомления, а не их доставка реальному клиенту.

## Сопоставление багов с автоматическими воспроизведениями

Файл: `src/test/java/ru/printlab/AdversarialWorkflowTest.java`. Эти тесты фиксируют нынешнее ошибочное поведение. После исправления нужно изменить ожидания на корректные.

| Баг в отчёте | Метод |
|---|---|
| 1. Ответ чужому клиенту | explicitReplyToFirstClientIsSentToSecondClientWhenAnotherReplyDialogIsActive |
| 2. Перепутаны параметры | oldParameterDialogSurvivesNavigationToAnotherStep |
| 3. Старая кнопка нового заказа | oldModelConsentBypassesNewOrderConfirmation |
| 4. Пустой черновик при неверном коде | invalidPromoCommandCreatesAnEmptyDraft |
| 5. Ответ по неверному заказу клиента | customerExplicitReplyToFirstOrderIsRecordedAgainstActiveSecondOrder |
| 6. Старый ввод после меню доставки | deliveryMenuDoesNotCancelExistingNameInput |
| 7. СДЭК при самовывозе | selfPickupStillOffersAndSendsCdekShippingNotification |
| 8. Команда меняет удалённый заказ | hiddenPaidOrderCanStillBeMovedByAdminCommand |

Основные места для исправления: `OperatorPanel.input` — приоритет reply; `CustomerFlow.input` — reply клиента; `CustomerFlow.callback` — сброс диалога на переходах и проверка нового заказа; `BotCommands.handle` — создание черновика до валидации команд; `OrderService.production/change` — deleted и способ получения.

## Данные реального прогона

| Объект | Наблюдение |
|---|---|
| №17 | Benchy: 17,963523864746094 г / 68,30865885416667 мин. Воспроизведено количество 30 вместо заполнения 30. FIRST50 вводился многократно, без платежа. Ручная цена 0 разрешена; затем 150, скидка 75, итог 75. СДЭК вне чека. Катушка в снимке 700/1000 не изменилась после изменения каталога на 900/500 |
| №18 | Две фотографии, описание, клиентский вопрос, ответ/фото оператора. Неверный PNG на готовом STL отклонён; правильный Benchy передан клиенту, модель `f17bc1ba-a1bf-4240-85a7-1cf9e13f7e0a` |
| №18, повторный расчёт | Bambu HTTP: 17,963529586791992 г / 68,31582845052084 мин, цена 125,74 ₽; расходы до удержаний 70,41 ₽. Сначала REVIEW_REQUIRED, сумма скрыта |
| №18, получение/публикация | Бауманская, бесплатно; quote `f5fd5855-c9dd-4ad4-8423-2cf6733e763d`, опубликован оператором, принят клиентом |
| №18, реквизиты | Тестовый текст с «НЕ ОПЛАЧИВАТЬ» из группы увиден в клиентском чате; ответ клиента записан CUSTOMER_MESSAGE и доставлен группе |
| №18, неверная оплата | `/paid 18 125.73 QA_BREAK_WRONG_AMOUNT` отклонён при сумме 125,74; `AB` отклонён; PRINTING и COMPLETED до оплаты отклонены. paid_at остался NULL |
| №19 | Создан старым consent:model при существующем DRAFT №18: обход предупреждения подтверждён production-обработчиком |
| QA_BREAK_DATE | Создание, неверные даты/проценты/лимиты, сохранение, повтор сохранения, выключение, удаление/восстановление. Итог enabled=false, deleted=true |
| QA_BREAK Бирюзовый | Согласование своего цвета, катушка, редактирование полей, переключение доступности, удаление. Итог available=false, deleted=true |

## Финальный контроль сервера

- №17/18/19: CANCELLED, deleted=true, paid_at=NULL; тестовых платежей 0.
- FIRST50: max_users=2, used_users=0, used_orders=0.
- FIRST30: max_users=8, prerequisite_paid_users=2, used_users=0, used_orders=0.
- №12/16: EXPIRED ещё до продолжения теста, не редактировались.
- outbox: pending=0, failed=0. Клиентский текст, тестовые реквизиты и отказ фото в текстовом вопросе delivered=true.
- Health UP, контейнер healthy; дополнительный Java-процесс остановлен, в контейнере остался основной бот. Временные QA-классы удалены с сервера и из контейнера. Перезапуска/деплоя приложения не было.

## Повторить автоматическую часть

Из корня проекта: `./mvnw -B -ntp test`.

Для отдельной PostgreSQL задать TEST_DATABASE_URL, TEST_DATABASE_USER, TEST_DATABASE_PASSWORD. **Никогда не указывать production:** BotTestSupport очищает тестовые таблицы перед каждым тестом.

В первоначальном H2-запуске песочница запретила локальный сокет mock HTTP-сервера: Socket Operation not permitted. Повтор с разрешённым локальным доступом прошёл; это ограничение среды, не баг приложения.

Три SlicerCliTest пропущены: не заданы SLICER_TEST_EXECUTABLE и SLICER_TEST_PROFILES. Реальный серверный Bambu проверен отдельно на Benchy, но это не заменяет CLI-проверку партий и физическую печать.

Итоги финальных прогонов и перечень тестов: [adversarial-test-run-summary.txt](adversarial-test-run-summary.txt), [adversarial-test-cases.csv](adversarial-test-cases.csv).
