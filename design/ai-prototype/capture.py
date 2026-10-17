"""Capture the installed, synthetic-only AI prototype. No real health app is opened."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
parser.add_argument('--adb', default=os.environ.get('ADB', str(Path.home()/'.local/share/health-connect-tools/platform-tools/adb')))
args = parser.parse_args()
adb = [args.adb] + (['-s', args.serial] if args.serial else [])
root = Path(__file__).parent/'screenshots'
root.mkdir(exist_ok=True)
component = 'app.scalemate.prototype/app.scalemate.prototype.PrototypeActivity'

def run(*cmd):
    return subprocess.check_output(adb+list(cmd), stderr=subprocess.STDOUT)

def tree():
    run('shell','uiautomator','dump','/sdcard/scalemate-ai-prototype-ui.xml')
    return ET.fromstring(run('shell','cat','/sdcard/scalemate-ai-prototype-ui.xml'))

def swipe_up():
    run('shell','input','swipe','540','1750','540','800','300')
    time.sleep(.25)

def tap(text, scroll=False, prefix=False):
    for _ in range(6 if scroll else 1):
        found = [n for n in tree().iter('node') if any((v.startswith(text) if prefix else v==text) for v in (n.get('text',''),n.get('content-desc','')))]
        if found:
            bounds = list(map(int,re.findall(r'\d+',found[-1].get('bounds'))))
            a,b,c,d = bounds
            if scroll and d>2250:
                swipe_up(); continue
            run('shell','input','tap',str((a+c)//2),str((b+d)//2))
            time.sleep(.4)
            print('TAP',text,flush=True)
            return
        if scroll: swipe_up()
    raise RuntimeError('Missing control: '+text)

def back():
    run('shell','input','keyevent','4');time.sleep(.3)

def start():
    run('shell','am','start','-S','-n',component);time.sleep(.8)

def shot(name):
    foreground=run('shell','dumpsys','activity','activities').decode()
    resumed=[line for line in foreground.splitlines() if 'ResumedActivity' in line or 'topResumedActivity' in line]
    assert any('app.scalemate.prototype/' in line for line in resumed),resumed
    (root/(name+'.png')).write_bytes(run('exec-out','screencap','-p'))
    print('SHOT',name,flush=True)

start()
shot('01-food-home')
tap('记录食物');shot('02-add-food-sheet')
tap('拍照识别');shot('03-mock-camera')
tap('使用这张示例照片');shot('04-photo-description')
tap('切换 ›');shot('05-vision-service-picker');back()
tap('开始分析');time.sleep(1.5);shot('06-food-confirmation')
tap('照片这碗的 1/3');shot('07-food-portions')
tap('修改 水煮牛肉');shot('08-food-edit-dialog');tap('取消')
tap('常吃 / 历史',scroll=True);shot('09-favorite-food-picker')
tap('历史食物');shot('10-food-history-picker')
tap('水煮牛肉');tap('添加 1 项到这一餐');shot('11-mixed-food-editor')
tap('确认保存 ·',prefix=True);shot('12-meal-saved')
tap('回到饮食');tap('健康');shot('13-health-home')
tap('查看本周观察  →');shot('14-health-report');swipe_up();shot('15-health-report-more')
tap('继续问这份报告',scroll=True);shot('16-health-chat-prompt')
tap('发送 · 模拟回答');time.sleep(1.5);shot('17-health-chat-answer')
tap('导出提问');shot('18-export-options')
tap('预览 JSON 与 Prompt');shot('19-export-json')
tap('Prompt');shot('20-export-prompt')
start();tap('设置');shot('21-settings')
tap('AI 服务');shot('22-ai-services')
tap('编辑服务');shot('23-ai-service-editor');back()
tap('食物图片识别',scroll=True);shot('24-capability-default-picker');back();back()
tap('主动提醒');shot('25-reminder-settings')
tap('预览一条提醒',scroll=True);shot('26-reminder-preview');back();back()
tap('健康数据');shot('27-health-data');swipe_up();shot('28-health-data-more');back()
tap('记录');shot('29-food-records');tap('体重');shot('30-weight-records')
tap('称重');shot('31-weigh-placeholder');tap('饮食')
print('COMPLETE: 31 screenshots',flush=True)
