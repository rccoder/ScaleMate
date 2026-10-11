"""Capture only this prototype's own screens from a connected Android device."""
import subprocess
import time
from pathlib import Path

ADB = Path.home() / '.local/share/health-connect-tools/platform-tools/adb'
TARGET = 'local.scalemate.design/.MainActivity'
OUT = Path(__file__).parent / 'screenshots'
OUT.mkdir(exist_ok=True)
for page in ['welcome', 'setup', 'devices', 'home', 'countdown', 'success', 'records', 'settings']:
    subprocess.run([str(ADB), 'shell', 'am', 'force-stop', 'local.scalemate.design'], check=True, capture_output=True)
    subprocess.run([str(ADB), 'shell', 'am', 'start', '-W', '-n', TARGET, '--es', 'screen', page, '--ez', 'capture', 'true'], check=True, capture_output=True)
    time.sleep(1.5)
    focus = subprocess.check_output([str(ADB), 'shell', 'dumpsys', 'window'], text=True)
    if not any('mCurrentFocus' in line and 'local.scalemate.design' in line for line in focus.splitlines()):
        raise RuntimeError('Prototype is not focused; refusing to capture another app.')
    with (OUT / f'{page}.png').open('wb') as output:
        subprocess.run([str(ADB), 'exec-out', 'screencap', '-p'], stdout=output, check=True)
    print(f'Captured {page}', flush=True)
