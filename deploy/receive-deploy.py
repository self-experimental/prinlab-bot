#!/usr/bin/python3
"""Root-owned forced SSH command. CI can replace only its component image.

Install outside the deploy user's writable directories. Compose, this receiver,
and the smoke fixture are changed manually, never supplied by an SSH payload.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path('/opt/printlab-bot-backend')
STATE = ROOT / 'state'
COMPOSE = ROOT / 'compose.yaml'
ENV_FILE = ROOT / 'config' / 'compose.env'
SMOKE = ROOT / 'smoke.py'
REPOSITORIES = {'bot': 'ghcr.io/self-experimental/prinlab-bot'}


class DeploymentRejected(ValueError):
    """Only fixed, non-sensitive diagnostic messages may reach CI logs."""


def validate(component, payload):
    if component not in REPOSITORIES or not isinstance(payload, dict):
        raise DeploymentRejected('Unknown component or invalid payload')
    if not re.fullmatch(r'[0-9a-f]{40}', payload.get('revision', '')):
        raise DeploymentRejected('Invalid revision')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', payload.get('digest', '')):
        raise DeploymentRejected('Invalid digest')
    for name in ('run_number', 'run_attempt'):
        if type(payload.get(name)) is not int or not 0 < payload[name] < 10**12:
            raise DeploymentRejected('Invalid workflow sequence')
    if not re.fullmatch(r'[A-Za-z0-9_\[\]-]{1,100}', payload.get('registry_user', '')):
        raise DeploymentRejected('Invalid registry user')
    # GitHub tokens are opaque: stateless GITHUB_TOKEN values include JWT dots
    # and base64url hyphens. Bound the transport, not the credential format.
    token = payload.get('registry_token')
    if (not isinstance(token, str) or not 1 <= len(token) <= 4096
            or not all(0x21 <= ord(char) <= 0x7e for char in token)):
        raise DeploymentRejected('Invalid registry token')
    return REPOSITORIES[component] + '@' + payload['digest']


def atomic_write(path, content):
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def read_env():
    values = {}
    for line in ENV_FILE.read_text().splitlines():
        if line and not line.startswith('#'):
            name, value = line.split('=', 1)
            values[name] = value
    return values


def write_env(values):
    atomic_write(ENV_FILE, ''.join(f'{k}={v}\n' for k, v in sorted(values.items())))


def run(args, **kwargs):
    # Never include payloads, tokens, subprocess input or environment in errors.
    result = subprocess.run(args, timeout=kwargs.pop('timeout', 300),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        raise RuntimeError('Command failed: ' + args[0])
    return result.stdout.decode().strip()


def compose(component):
    run(['/usr/bin/docker', 'compose', '--env-file', str(ENV_FILE), '-f', str(COMPOSE),
         'up', '-d', '--no-deps', '--pull', 'never', '--wait', '--wait-timeout', '150', component],
        timeout=390)


def check(component):
    run(['/usr/bin/python3', str(SMOKE)], timeout=240)


def deploy(component, payload):
    image = validate(component, payload)
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (STATE / 'deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state_file = STATE / (component + '.json')
        previous = json.loads(state_file.read_text()) if state_file.exists() else None
        sequence = (payload['run_number'], payload['run_attempt'])
        if previous and sequence < (previous['run_number'], previous['run_attempt']):
            raise DeploymentRejected('Stale deployment rejected')
        # Login data exists only during this pull, never in /root/.docker/config.json.
        with tempfile.TemporaryDirectory(prefix='printlab-registry-') as directory:
            env = {**os.environ, 'DOCKER_CONFIG': directory}
            run(['/usr/bin/docker', 'login', 'ghcr.io', '-u', payload['registry_user'],
                 '--password-stdin'], input=payload['registry_token'].encode(), env=env, timeout=30)
            run(['/usr/bin/docker', 'pull', '--platform', 'linux/amd64', image], env=env, timeout=300)
        revision = run(['/usr/bin/docker', 'image', 'inspect', '--format',
                        '{{ index .Config.Labels "org.opencontainers.image.revision" }}', image])
        if revision != payload['revision']:
            raise DeploymentRejected('Image revision mismatch')
        platform = run(['/usr/bin/docker', 'image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', image])
        if platform != 'linux/amd64':
            raise DeploymentRejected('Wrong image platform')
        values = read_env()
        key = component.upper() + '_IMAGE'
        old_image = values[key]
        values[key] = image
        print(f'Deploying {component} {revision}', flush=True)
        try:
            backup = run(['/usr/bin/docker', 'compose', '--env-file', str(ENV_FILE), '-f', str(COMPOSE),
                'exec', '-T', 'db', 'pg_dump', '-U', 'printlab', '-d', 'printlab'], timeout=60)
            atomic_write(ROOT / 'backups' / (str(int(time.time())) + '.sql'), backup + '\n')
            write_env(values)
            compose(component)
            check(component)
            atomic_write(state_file, json.dumps(dict(revision=revision, image=image,
                previous_image=old_image, run_number=payload['run_number'],
                run_attempt=payload['run_attempt'], deployed_at=int(time.time()))) + '\n')
        except BaseException:
            # An interrupted SSH/CI job must not leave an unverified image active.
            for signum in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
                signal.signal(signum, signal.SIG_IGN)
            values[key] = old_image
            write_env(values)
            compose(component)
            print('Deployment failed; previous image restored', flush=True)
            raise
        print(f'Deployed {component} {revision}', flush=True)


def interrupted(signum, frame):
    raise RuntimeError('Deployment interrupted')


def main():
    os.umask(0o077)
    for signum in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT, signal.SIGALRM):
        signal.signal(signum, interrupted)
    if len(sys.argv) != 2:
        raise DeploymentRejected('Component required')
    signal.alarm(15)
    raw = sys.stdin.buffer.read(8193)
    signal.alarm(0)
    if len(raw) > 8192:
        raise DeploymentRejected('Payload too large')
    deploy(sys.argv[1], json.loads(raw))


if __name__ == '__main__':
    try:
        main()
    except DeploymentRejected as error:
        print('Deployment rejected: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        print('Deployment rejected or failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
