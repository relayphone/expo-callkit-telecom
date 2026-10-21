#!/usr/bin/env python3
"""Follow XCTest's AUT reinstalls; never assume its initial data container survives."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

device, bundle, destination = sys.argv[1:]
destination = Path(destination)
previous = None
while True:
    try:
        result = subprocess.run(['xcrun', 'simctl', 'get_app_container', device, bundle, 'data'],
                                capture_output=True, text=True, timeout=10)
        container = result.stdout.strip()
        if result.returncode == 0 and container and container != previous:
            descriptor, temporary = tempfile.mkstemp(prefix='.aut-container-', dir=destination.parent)
            try:
                with os.fdopen(descriptor, 'w') as output:
                    output.write(container + '\n')
                os.replace(temporary, destination)
            finally:
                if os.path.exists(temporary): os.unlink(temporary)
            previous = container
    except subprocess.TimeoutExpired:
        pass
    time.sleep(0.5)
