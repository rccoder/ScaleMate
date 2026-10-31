#!/usr/bin/env python3
"""Drive one scale through the real emulator UI using debug-only mock BLE data."""

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = "emulator-5556"
PKG = "app.scalemate.fooddev"
ACTIVITY = f"{PKG}/app.scalemate.MainActivity"
EVIDENCE = Path(__file__).resolve().parent


def adb(*args, text=False, check=True):
    cmd = ["adb", "-s", SERIAL, *map(str, args)]
    process = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
    )
    if check and process.returncode != 0:
        raise RuntimeError(f"adb failed: {' '.join(cmd)}\n{process.stderr}")
    return process.stdout if text else process.stdout


def shell(command, text=True, check=True):
    return adb("shell", command, text=text, check=check)


def dump():
    shell("uiautomator dump /sdcard/window.xml")
    raw = adb("exec-out", "cat", "/sdcard/window.xml", text=True)
    return ET.fromstring(raw)


def texts(root):
    output = []
    for node in root.iter("node"):
        text = (node.attrib.get("text") or "").strip()
        if text:
            output.append((text, node.attrib.get("bounds", "")))
    return output


def bounds_center(value):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value)
    if not match:
        raise ValueError(value)
    left, top, right, bottom = map(int, match.groups())
    return ((left + right) // 2, (top + bottom) // 2)


def tap(x, y):
    shell(f"input touchscreen swipe {x} {y} {x} {y} 120")
    time.sleep(0.25)


def click_text(root, candidate):
    matches = [
        node
        for node in root.iter("node")
        if (node.attrib.get("text") or "").strip() == candidate
    ]
    if not matches:
        return False
    x, y = bounds_center(matches[-1].attrib["bounds"])
    tap(x, y)
    return True


def wait_text(expected, timeout=6, predicate=None):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = dump()
        found = [text for text, _ in texts(last)]
        if any(text == expected if predicate is None else predicate(text) for text in found):
            return last
        time.sleep(0.2)
    visible = [text for text, _ in texts(last or ET.Element("x"))][:80]
    raise RuntimeError(f"text not found: {expected}; visible={visible}")


def screenshot(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(adb("exec-out", "screencap", "-p"))


def sql(query):
    remote = f"run-as {PKG} sqlite3 -separator '|' databases/scalemate.db \"{query}\""
    return shell(remote).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--weight", type=float, required=True)
    parser.add_argument("--index", type=int, required=True)
    parser.add_argument("--evidence-root", type=Path, default=EVIDENCE)
    parser.add_argument("--keep-scale", action="store_true")
    parser.add_argument("--keep-existing", action="store_true")
    parser.add_argument("--remove-after", action="store_true")
    args = parser.parse_args()

    log = {
        "modelId": args.model_id,
        "displayName": args.name,
        "weightKg": args.weight,
        "steps": [],
    }
    model_dir = args.evidence_root / f"{args.index:02d}-{args.model_id}"
    model_dir.mkdir(parents=True, exist_ok=True)

    shell(f"am force-stop {PKG}")
    shell("logcat -c")
    shell(
        f"am start -W -n {ACTIVITY} --ez mockScales true "
        f"--es mockScaleModelId {args.model_id} --es startPage settings"
    )
    wait_text("AI 服务", timeout=4)
    log["steps"].append("cold-start-settings")

    settings = dump()
    if not (
        click_text(settings, "我的体重秤")
        or click_text(settings, "添加体重秤")
    ):
        raise RuntimeError("settings scale row not found")
    wait_text("扫描附近的秤", timeout=4)
    log["steps"].append("settings-to-devices")

    if not args.keep_existing:
        removed = 0
        while True:
            devices = dump()
            visible = [text for text, _ in texts(devices)]
            if "移除体重秤" not in visible:
                break
            if not click_text(devices, "移除体重秤"):
                raise RuntimeError("existing scale remove button not found")
            removed += 1
            time.sleep(0.5)
            if removed > 20:
                raise RuntimeError("too many scales remained")
        if removed:
            log["steps"].append(f"removed-{removed}-existing-scale-bindings")
            wait_text("扫描附近的秤", timeout=4)

    devices = dump()
    if not click_text(devices, "扫描附近的秤"):
        raise RuntimeError("scan button not found")
    found = wait_text(args.name, timeout=5)
    screenshot(model_dir / "01-found.png")
    log["steps"].append("scan-found")

    if not click_text(found, args.name):
        raise RuntimeError("candidate card not found")
    confirm = wait_text("确认这台秤", timeout=4)
    if not any(args.name in text for text, _ in texts(confirm)):
        raise RuntimeError("confirm dialog missing model name")
    log["steps"].append("confirm-dialog")

    if not click_text(confirm, "添加并开始试称"):
        raise RuntimeError("bind button not found")
    trial = wait_text("读数一致，继续", timeout=6)
    weight_text = f"{args.weight:.2f}"
    if weight_text not in [text for text, _ in texts(trial)]:
        raise RuntimeError(f"trial weight missing: {weight_text}")
    screenshot(model_dir / "02-trial.png")
    log["steps"].append("trial-stable")

    if not click_text(trial, "读数一致，继续"):
        raise RuntimeError("verify button not found")
    choice = wait_text("每次手动保存", timeout=4)
    log["steps"].append("verify-choice")

    if not click_text(choice, "每次手动保存"):
        raise RuntimeError("manual save option not found")
    verified = wait_text("设置已完成", timeout=6)
    screenshot(model_dir / "04-verified.png")
    log["steps"].append("setup-verified")

    if not click_text(verified, "确认并保存"):
        raise RuntimeError("save button not found")
    time.sleep(0.7)
    after = dump()
    visible = [text for text, _ in texts(after)]
    if "先保存在本机" in visible:
        if not click_text(after, "先保存在本机"):
            raise RuntimeError("local save option not found")
        time.sleep(0.8)
        after = dump()
        visible = [text for text, _ in texts(after)]
    screenshot(model_dir / "06-saved.png")
    if not any("已本地保存" in text or "待授权" in text for text in visible):
        raise RuntimeError(f"saved state missing; visible={visible[:80]}")
    log["steps"].append("measurement-saved")

    scale_row = sql(
        "select name,address,verified,modelId from scales "
        f"where modelId='{args.model_id}' order by rowid desc limit 1;"
    )
    measurement_row = sql(
        "select source,kg,status,modelId from measurements "
        f"where modelId='{args.model_id}' order by time desc limit 1;"
    )
    if not scale_row or not measurement_row:
        raise RuntimeError(
            f"database rows missing scale={scale_row!r} measure={measurement_row!r}"
        )
    log["databaseScale"] = scale_row
    log["databaseMeasurement"] = measurement_row
    log["databaseScales"] = sql(
        "select id,name,address,verified,modelId from scales order by rowid;"
    )

    if args.remove_after and not args.keep_scale:
        tap(960, 2260)
        wait_text("AI 服务", timeout=4)
        settings = dump()
        if not click_text(settings, "我的体重秤"):
            raise RuntimeError("scale management row missing after save")
        wait_text("移除体重秤", timeout=4)
        remove = dump()
        if not click_text(remove, "移除体重秤"):
            raise RuntimeError("remove button not found")
        wait_text("扫描附近的秤", timeout=5)
        remaining = sql(
            f"select count(*) from scales where modelId='{args.model_id}';"
        )
        if remaining != "0":
            raise RuntimeError(f"scale not removed: {remaining}")
        log["steps"].append("scale-removed")

    log_path = model_dir / "validation.json"
    log_path.write_text(
        json.dumps(log, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(log, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
