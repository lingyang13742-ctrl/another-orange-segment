import sys  
path = r'D:\a13\orangechat\app\src\main\java\me\rerere\rikkahub\ui\pages\setting\SettingSystemToolsPage.kt'  
with open(path, 'r', encoding='utf-8') as f: lines = f.readlines()  
result = []  
for i, line in enumerate(lines):  
    stripped = line.rstrip()  
    if stripped == '            }':  
        prev_idx = len(result) - 1  
        while prev_idx >= 0 and result[prev_idx].strip() == '': prev_idx -= 1  
        if prev_idx >= 0 and result[prev_idx].rstrip() == '                }':  
            continue  # skip this orphan closing brace  
    result.append(line)  
with open(path, 'w', encoding='utf-8') as f: f.writelines(result)  
print('done', len(lines), len(result))  
