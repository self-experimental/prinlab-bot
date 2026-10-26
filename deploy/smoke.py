#!/usr/bin/env python3
"""Check readiness and real slicing through the bot's private proxy, without sending Telegram messages."""
import json
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path('/opt/printlab-bot-backend')


def check():
    with urllib.request.urlopen('http://127.0.0.1:8082/actuator/health', timeout=10) as response:
        if json.load(response)['status'] != 'UP':
            raise RuntimeError('Bot is not healthy')
    config = dict(line.split('=', 1) for line in (ROOT / 'config/bot.env').read_text().splitlines()
                  if line and not line.startswith('#') and '=' in line)
    token = config.get('TELEGRAM_BOT_TOKEN', '')
    if config.get('BOT_ENABLED', 'false').lower() != 'true':
        raise RuntimeError('Telegram polling is disabled')
    try:
        with urllib.request.urlopen('https://api.telegram.org/bot' + token + '/getMe', timeout=15) as response:
            if not json.load(response).get('ok'):
                raise RuntimeError('Telegram rejected bot credentials')
        with urllib.request.urlopen('https://api.telegram.org/bot' + token + '/getWebhookInfo', timeout=15) as response:
            if json.load(response).get('result', {}).get('url'):
                raise RuntimeError('Webhook conflicts with polling')
    except Exception:
        raise RuntimeError('Telegram readiness check failed') from None
    args = ['docker', 'compose', '--env-file', str(ROOT / 'config/compose.env'), '-f', str(ROOT / 'compose.yaml'),
            'exec', '-T', 'bot', 'curl', '--fail', '--silent', '--show-error', '--max-time', '210',
            '-F', 'file=@/app/smoke.stl', '-F', 'materialProfileId=prusament-pla',
            '-F', 'priority=BALANCED', '-F', 'layerHeightMm=0.20', '-F', 'minimumInfillPercent=15',
            '-F', 'minimumPerimeters=2', '-F', 'supportMode=AUTO', '-F', 'maxPrintTimeMin=0',
            '-F', 'maxMaterialConsumptionG=0', 'http://slicer-proxy:8080/api/print-plans/analyze-stl']
    result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=220, check=True)
    body = json.loads(result.stdout)
    baseline = next(c for c in body['candidates'] if c['infillPercent'] == 15
                    and c['perimeters'] == 2 and c['layerHeightMm'] == .20)
    if not (baseline['estimatedMaterialG'] > 0 and baseline['estimatedPrintTimeMin'] > 0
            and baseline['meetsConstraints'] and body['engineVersion']):
        raise RuntimeError('Invalid slicer metrics')
    print('Bot, PostgreSQL, Telegram credentials and native Bambu slicing verified')


if __name__ == '__main__':
    try:
        check()
    except Exception as error:
        raise SystemExit('Production smoke failed: ' + type(error).__name__)
