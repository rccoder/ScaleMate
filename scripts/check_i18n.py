"""Check both locales have matching keys and formatting arguments."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

base = Path(__file__).resolve().parents[1] / 'app/src/main/res'
def strings(locale):
    return {e.attrib['name']: e.text or '' for e in ET.parse(base / locale / 'strings.xml').getroot()}
english, chinese = strings('values'), strings('values-zh')
assert english.keys() == chinese.keys(), 'Locale keys differ'
for key in english:
    assert re.findall(r'%\d+\$[ds]', english[key]) == re.findall(r'%\d+\$[ds]', chinese[key]), key
    assert english[key].strip() and chinese[key].strip(), key
assert english['app_name'] == 'ScaleMate'
assert chinese['app_name'] == '称伴'
print(f'I18N: {len(english)} paired strings; names and format arguments match')
