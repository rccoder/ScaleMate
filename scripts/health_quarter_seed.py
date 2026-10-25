#!/usr/bin/env python3
"""Build and reuse the existing emulator seed source; never operate on a phone."""
import argparse
import json
import re
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "docs/testing/fixtures/health-quarter"
PACKAGE = "app.scalemate.healthseed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "seed", "change", "cleanup"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    if not args.serial.startswith("emulator-") or b"sdk_gphone" not in subprocess.check_output(adb + ["shell", "getprop", "ro.product.model"]):
        raise SystemExit("Only an Android emulator is allowed")
    if not subprocess.check_output(adb + ["shell", "pm", "path", PACKAGE]).strip():
        raise SystemExit("The fixed emulator seed source must already be installed")
    with tempfile.TemporaryDirectory(prefix="scalemate-health-quarter-") as directory:
        build = Path(directory)
        classes = build / "classes"
        dex = build / "dex"
        classes.mkdir()
        dex.mkdir()
        tools = args.sdk / "build-tools/36.0.0"
        platform = args.sdk / "platforms/android-36/android.jar"
        subprocess.run(["javac", "-encoding", "UTF-8", "-source", "17", "-target", "17", "-cp", str(platform), "-d", str(classes), str(FIXTURE / "SeedActivity.java")], check=True)
        subprocess.run([str(tools / "d8"), "--lib", str(platform), "--output", str(dex), *map(str, classes.rglob("*.class"))], check=True)
        unsigned = build / "unsigned.apk"
        subprocess.run([str(tools / "aapt"), "package", "-f", "-M", str(FIXTURE / "AndroidManifest.xml"), "-S", str(FIXTURE / "res"), "-I", str(platform), "-F", str(unsigned)], check=True)
        with zipfile.ZipFile(unsigned, "a") as archive:
            archive.write(dex / "classes.dex", "classes.dex")
        aligned = build / "aligned.apk"
        apk = build / "healthseed.apk"
        subprocess.run([str(tools / "zipalign"), "-f", "4", str(unsigned), str(aligned)], check=True)
        subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(Path.home() / ".android/debug.keystore"), "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(apk), str(aligned)], check=True)
        subprocess.run(adb + ["install", "--no-incremental", "-r", str(apk)], check=True)
    permissions = [p.get("{http://schemas.android.com/apk/res/android}name") for p in ET.parse(FIXTURE / "AndroidManifest.xml").getroot().findall("uses-permission")]
    # Package replacement can asynchronously reset health grants. Verify that they settle
    # before launching; granting once immediately after install races the permission service.
    stable = 0
    for _ in range(20):
        state = subprocess.check_output(adb + ["shell", "dumpsys", "package", PACKAGE], text=True)
        missing = [p for p in permissions if not re.search(re.escape(p) + r": granted=true", state)]
        if missing:
            stable = 0
            for permission in missing:
                subprocess.run(adb + ["shell", "pm", "grant", PACKAGE, permission], check=True)
        else:
            stable += 1
            if stable == 3:
                break
        time.sleep(0.5)
    else:
        raise SystemExit("The emulator seed source's write permissions did not settle")
    if args.action == "prepare":
        return
    subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], check=True)
    subprocess.run(adb + ["shell", "run-as", PACKAGE, "rm", "-f", "files/quarter-result.json"], check=True)
    subprocess.run(adb + ["shell", "am", "start", "-W", "-n", PACKAGE + "/.SeedActivity", "--es", "action", args.action], check=True)
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        read = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "files/quarter-result.json"], capture_output=True)
        if read.returncode == 0 and read.stdout.strip():
            try:
                result = json.loads(read.stdout)
            except json.JSONDecodeError:
                time.sleep(0.1)
                continue
            print(json.dumps(result, ensure_ascii=False))
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
            if not result["ok"]:
                raise SystemExit(1)
            return
        time.sleep(1)
    raise SystemExit("Quarter seed timed out; inspect its status before retrying")


if __name__ == "__main__":
    main()
