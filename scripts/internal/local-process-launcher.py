#!/usr/bin/env python3

import os
import sys
from pathlib import Path


repo = Path(__file__).resolve().parent.parent.parent
wrapper = (repo / "gradlew").resolve()
if len(sys.argv) < 2 or Path(sys.argv[1]).resolve() != wrapper:
    raise SystemExit("local process launcher only accepts this repository's Gradle wrapper")

os.chdir(repo)
os.setsid()
os.execv(wrapper, [str(wrapper), *sys.argv[2:]])
