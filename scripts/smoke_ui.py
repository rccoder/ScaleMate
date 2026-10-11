"""Real-app navigation and language smoke test. No Bluetooth or health writes."""
import os
import re
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET

ADB = os.environ.get('ADB', str(Path.home() / '.local/share/health-connect-tools/platform-tools/adb'))
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/testing/screenshots'
OUT.mkdir(exist_ok=True)
def run(*args):
    return subprocess.check_output([ADB, *args], text=True)
def tree():
    focus = run('shell', 'dumpsys', 'window')
    assert any('mCurrentFocus' in line and 'app.scalemate/' in line for line in focus.splitlines()), 'Another app is focused'
    run('shell', 'uiautomator', 'dump', '/data/local/tmp/scalemate-production-ui.xml')
    return ET.fromstring(run('shell', 'cat', '/data/local/tmp/scalemate-production-ui.xml'))
def click(*labels, optional=False):
    root = tree()
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter('node'):
        if node.get('text') not in labels:
            continue
        target = node
        while target is not None and target.get('clickable') != 'true':
            target = parents.get(target)
        if target is None:
            continue
        p = list(map(int, re.findall(r'\d+', target.get('bounds', ''))))
        run('shell', 'input', 'tap', str((p[0]+p[2])//2), str((p[1]+p[3])//2))
        time.sleep(.5)
        return True
    if optional:
        return False
    raise AssertionError('Control missing: ' + str(labels))
def screenshot(name):
    tree()  # Check focus before capturing.
    with (OUT / name).open('wb') as f:
        subprocess.run([ADB, 'exec-out', 'screencap', '-p'], stdout=f, check=True)
run('shell', 'am', 'start', '-W', '-n', 'app.scalemate/.MainActivity')
time.sleep(1)
if click('Get started', '开始设置', optional=True):
    click('Continue with local records', '先使用本地记录')
click('Settings', '设置')
click('App language', '应用语言')
click('English')
time.sleep(1)
assert any(n.get('text') == 'Settings' for n in tree().iter('node'))
screenshot('settings-en.png')
click('App language');click('中文');time.sleep(1)
assert any(n.get('text') == '设置' for n in tree().iter('node'))
screenshot('settings-zh.png')
click('记录');assert any(n.get('text') == '确认保存的称重记录会显示在这里。' for n in tree().iter('node')), 'Expected clean first-launch records'
click('称重')
print('PASS: real-app navigation, English/Chinese switch, and empty records')
