"""Create release files using the version in app/build.gradle.kts as the source of truth."""
import re
import shutil
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', source).group(1)
code = int(re.search(r'versionCode\s*=\s*([0-9]+)', source).group(1))
release = root / "release"
release.mkdir(exist_ok=True)
apk = release / f"pocket-local-assistant-{version}-arm64.apk"
shutil.copyfile(root / "app/build/outputs/apk/release/app-release.apk", apk)
subprocess.run([sys.executable, str(root / "scripts/make_update.py"), str(apk), "--version", version, "--code", str(code), "--output", str(release / "update.json")], check=True)
