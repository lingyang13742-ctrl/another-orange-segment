import getpass, json, urllib.request, urllib.error
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from threading import Lock

api_key=getpass.getpass('DeepSeek API key (hidden): ').strip()
headers={'Authorization':'Bearer '+api_key,'Content-Type':'application/json'}
try:
    req=urllib.request.Request('https://api.deepseek.com/v1/models', headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r: models=json.load(r)
except urllib.error.HTTPError as e:
    print('AUTH_CHECK_FAILED',e.code,e.read().decode()[:1500],flush=True)
    raise SystemExit(1)
ids=[m['id'] for m in models.get('data',[])]
print('AVAILABLE_MODELS', json.dumps(ids),flush=True)
model=next((m for m in ['deepseek-flash','deepseek-chat','deepseek-v4-flash'] if m in ids),None)
if not model: raise SystemExit('No recognized chat model in available list')
Path('_chk/deepseek-live-model.txt').write_text(model)
log_lock=Lock()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_POST(self):
        payload=self.rfile.read(int(self.headers['Content-Length']))
        data=json.loads(payload)
        # Model selection stays automatic. No tool_choice forcing or response rewriting.
        data['model']=model
        if model=='deepseek-flash': data['thinking']={'type':'disabled'}
        req=urllib.request.Request('https://api.deepseek.com/v1/chat/completions',data=json.dumps(data).encode(),headers=headers)
        record={'endpoint':'https://api.deepseek.com/v1/chat/completions','model':model,'request':data}
        try:
            with urllib.request.urlopen(req,timeout=90) as r: body=r.read(); status=r.status
        except urllib.error.HTTPError as e: body=e.read(); status=e.code
        except Exception as e:
            body=json.dumps({'error':{'message':str(e),'type':'proxy_network_error'}}).encode(); status=502
        record['http_status']=status
        try: record['response']=json.loads(body)
        except Exception: record['response_text']=body.decode(errors='replace')
        with log_lock:
            with Path('_chk/deepseek-live-api.jsonl').open('a') as f: f.write(json.dumps(record,ensure_ascii=False)+'\n')
        choice=(record.get('response',{}).get('choices') or [{}])[0]
        print('DEEPSEEK_RESPONSE',status,'model='+model,'tools_offered='+','.join(t.get('function',{}).get('name','') for t in data.get('tools',[])),'tools_selected='+','.join(t.get('function',{}).get('name','') for t in choice.get('message',{}).get('tool_calls',[])),flush=True)
        self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
print('PROXY_READY port=18766 model='+model,flush=True)
ThreadingHTTPServer(('127.0.0.1',18766),Handler).serve_forever()
