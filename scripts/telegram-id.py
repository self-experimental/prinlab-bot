#!/usr/bin/env python3
"""Find your numeric ID through your own bot before starting PrintLab."""

import argparse
import getpass
import json
import re
import sys
import urllib.error
import urllib.request


def request(token, method, payload):
    url = "https://api.telegram.org/bot" + token + "/" + method
    message = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(message, timeout=25) as response:
            content = response.read(2 * 1024 * 1024 + 1)
        if len(content) > 2 * 1024 * 1024:
            raise SystemExit("Ответ Telegram слишком большой. Повторите позже.")
        body = json.loads(content)
    except urllib.error.HTTPError as error:
        if error.code == 401:
            raise SystemExit("Telegram отклонил токен. Проверьте его в BotFather.") from None
        if error.code == 409:
            raise SystemExit("Telegram сообщает о конфликте. Остановите работающий экземпляр бота.") from None
        raise SystemExit("Ошибка Telegram HTTP " + str(error.code) + ".") from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        # Network exceptions can include the full URL and bot token.
        raise SystemExit("Не удалось связаться с Telegram. Проверьте сеть и повторите.") from None
    if not body.get("ok"):
        raise SystemExit("Telegram не выполнил запрос. Проверьте настройки бота.")
    return body["result"]


def main():
    parser = argparse.ArgumentParser(description="Узнать Telegram ID через своего бота, пока PrintLab остановлен.")
    parser.add_argument("--delete-webhook", action="store_true",
                        help="Снять прежний webhook, сохранив ожидающие сообщения.")
    args = parser.parse_args()
    token = getpass.getpass("Токен из BotFather (ввод скрыт, в файл не записывается): ").strip()
    if not re.fullmatch(r"[0-9]+:[A-Za-z0-9_-]{20,}", token):
        raise SystemExit("Неверный формат токена.")
    bot = request(token, "getMe", {})
    webhook = request(token, "getWebhookInfo", {})
    if webhook.get("url"):
        if not args.delete_webhook:
            raise SystemExit("У бота настроен webhook. Для перехода на PrintLab повторите с --delete-webhook.")
        request(token, "deleteWebhook", {"drop_pending_updates": False})
        print("Прежний webhook снят, ожидающие сообщения сохранены.")
    print("Откройте @" + bot["username"] + " в Telegram и отправьте /start.", flush=True)
    # No offset: the helper does not acknowledge or discard pending updates.
    updates = request(token, "getUpdates", {"timeout": 15, "limit": 100})
    accounts = {}
    for update in updates:
        message = update.get("message", {})
        chat = message.get("chat", {})
        sender = message.get("from", {})
        if chat.get("type") == "private" and sender.get("id") == chat.get("id") and not sender.get("is_bot"):
            accounts[sender["id"]] = sender.get("username", sender.get("first_name", "без имени"))
    if not accounts:
        print("Личных сообщений пока нет. Отправьте /start и повторите команду.")
        return
    for user_id, name in accounts.items():
        print(str(name) + ": Telegram ID " + str(user_id))
    print("Запишите ID своего аккаунта в TELEGRAM_ADMIN_IDS в .env. Несколько ID разделяются запятыми.")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        sys.exit("Ввод отменён.")
