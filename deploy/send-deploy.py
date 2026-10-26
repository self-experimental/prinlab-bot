#!/usr/bin/env python3
"""Send a digest and a short-lived registry token over a pinned SSH connection."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

payload = dict(revision=os.environ['GITHUB_SHA'], digest=sys.argv[1],
    run_number=int(os.environ['GITHUB_RUN_NUMBER']), run_attempt=int(os.environ['GITHUB_RUN_ATTEMPT']),
    registry_user=os.environ['GITHUB_ACTOR'], registry_token=os.environ['REGISTRY_TOKEN'])
with tempfile.TemporaryDirectory(prefix='printlab-deploy-') as directory:
    key = Path(directory) / 'key'
    key.write_text(os.environ['DEPLOY_SSH_KEY'] + '\n')
    key.chmod(0o600)
    known = Path(__file__).with_name('ssh_known_hosts').resolve()
    subprocess.run(['ssh', '-T', '-i', str(key), '-o', 'IdentitiesOnly=yes',
        '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
        '-o', 'UserKnownHostsFile=' + str(known), '-o', 'ConnectTimeout=15',
        '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=4',
        'printlab-bot-deploy@109.66.245.248'], input=json.dumps(payload).encode(),
        check=True, timeout=900)
