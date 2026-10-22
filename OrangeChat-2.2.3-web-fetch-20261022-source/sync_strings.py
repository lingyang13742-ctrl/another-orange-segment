import re
import os

src = r'd:/a13/orangechat/app/src/main/res/values-zh/strings.xml'
targets = [
    r'd:/a13/orangechat/app/src/main/res/values/strings.xml',
    r'd:/a13/orangechat/app/src/main/res/values-zh-rTW/strings.xml',
]

with open(src, 'r', encoding='utf-8') as f:
    text = f.read()

m = re.search(r'(<!\-\- 免责声明 / 法律信息 \-\->.*?)\n</resources>', text, re.S)
if not m:
    print('source block not found')
    exit(1)
block = m.group(1)

for t in targets:
    if not os.path.exists(t):
        print(f'skip missing {t}')
        continue
    with open(t, 'r', encoding='utf-8') as f:
        tt = f.read()
    if 'disclaimer_page_title' in tt:
        print(f'already has keys in {t}, skip')
        continue
    tt = tt.replace('</resources>', block + '\n</resources>')
    with open(t, 'w', encoding='utf-8') as f:
        f.write(tt)
    print(f'updated {t}')
