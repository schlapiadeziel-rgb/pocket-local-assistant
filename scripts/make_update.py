import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--version", required=True)
parser.add_argument("--code", type=int, required=True)
parser.add_argument("--repository", default="schlapiadeziel-rgb/pocket-local-assistant")
parser.add_argument("--output", type=Path, default=Path("update.json"))
args = parser.parse_args()
assert args.code > 0
assert args.apk.name == f"pocket-local-assistant-{args.version}-arm64.apk"
digest = hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest()
manifest = {
    "packageName": "cn.pocketai.assistant", "versionCode": args.code,
    "versionName": args.version, "tag": "v" + args.version,
    "fileName": args.apk.name, "size": args.apk.stat().st_size, "sha256": digest,
    "apkUrl": f"https://github.com/{args.repository}/releases/download/v{args.version}/{args.apk.name}"
}
args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
