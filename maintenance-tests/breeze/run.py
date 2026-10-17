import json, pathlib, sys, time, urllib.request

root=pathlib.Path(sys.argv[1]).resolve()
phase=sys.argv[2]
logfile=root/'evidence/runtime.log'
def log(): return logfile.read_text(encoding='utf8',errors='replace') if logfile.exists() else ''
def post(port,body):
    req=urllib.request.Request(f'http://127.0.0.1:{port}',json.dumps(body).encode(),{'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=10) as r:return r.read().decode()
def wait(text,offset=0,timeout=60):
    end=time.time()+timeout
    while time.time()<end:
        if text in log()[offset:]:return
        time.sleep(.2)
    raise RuntimeError('Timeout waiting for '+text+'\n'+log()[-3500:])
def command(player,cmd,expected):
    time.sleep(1.1)  # Real non-OP clients are subject to vanilla command-spam limits.
    offset=len(log());post(53517,{'action':'send','name':player,'command':'/breezeprobe '+cmd});wait(expected,offset)

wait('Done (')
status=json.loads(post(53517,{'action':'status'}))
for name in ['BreezeTestA','BreezeTestB']:
    if not any(x['name']==name and x['state']=='play' for x in status):post(53517,{'action':'connect','name':name})
time.sleep(8)
for name in ['BreezeTestA','BreezeTestB']:command(name,'home','HOME '+name+' success=true')
time.sleep(3)
for name in ['BreezeTestA','BreezeTestB']:command(name,'setup','SETUP '+name)
time.sleep(2)
cases=[('remote',True,'direct'),('remote',True,'native')]
if phase!='baseline':cases=[(mode,deny,kind) for mode in ['local','remote','none','player','wind'] for deny in [True,False] for kind in ['direct','native']]
for index,(mode,deny,kind) in enumerate(cases):
    label=f'{index}-{mode}-{deny}-{kind}'
    player='BreezeTestB' if mode=='player' else 'BreezeTestA'
    if mode=='player':command(player,'here','HERE '+player+' success=true')
    command(player,'charge '+mode,'CHARGE_READY '+mode)
    command('BreezeTestA',f'run {label} {str(deny).lower()} {kind}','CASE_DONE '+label)
    print('COMPLETED '+label,flush=True)
command('BreezeTestA','summary','SUMMARY ')
data=log()
summary=[x for x in data.splitlines() if 'SUMMARY passed=' in x][-1]
print(summary,flush=True)
if phase.startswith('baseline'):
    assert 'Creature.getTarget' in data or 'CraftMob.getTarget' in data
    assert '[WorldGuardBreezeProbe] FAIL ' in data
else:
    assert 'SUMMARY passed=91 failed=0' in summary, 'Incomplete test matrix'
    assert '[WorldGuardBreezeProbe] FAIL ' not in data, 'Probe assertions failed'
    assert 'Could not pass event' not in data, 'Plugin event dispatch failed'
    assert 'Thread failed main thread check' not in data, 'Region ownership violation'
(root/'evidence'/f'{phase}-result.json').write_text(json.dumps({'phase':phase,'cases':len(cases),'summary':summary,'expected_outcome':True},ensure_ascii=False,indent=2),encoding='utf8')
