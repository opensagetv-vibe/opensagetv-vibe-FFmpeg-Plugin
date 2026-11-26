#!/usr/bin/env python3
"""Read-only exact Non-Pro/stock232 producer evidence during an owned live marker."""
import argparse,hashlib,importlib.util,json,re,shlex,stat,time
from pathlib import Path
from urllib.request import urlopen

root=Path(__file__).resolve().parents[1];web=root.parent/'opensagetv-vibe-WebClient-Plugin'
if not web.is_dir():web=root.parent/'web-client-plugin'
spec=importlib.util.spec_from_file_location('commission',web/'scripts/commission-mobile.py')
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C);cfg=C.configuration()
p=argparse.ArgumentParser();p.add_argument('--ready-file',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
android=root.parent/'opensagetv-vibe-android-client'
if not android.is_dir():android=root.parent/'android-client'
allowed=(android/'artifacts/active/SETTINGS-003').resolve()
assert a.ready_file.resolve().is_relative_to(allowed) and a.output_dir.resolve()==allowed
deadline=time.monotonic()+180
while not a.ready_file.exists():
    if time.monotonic()>deadline:raise TimeoutError('Owned live marker unavailable')
    time.sleep(.25)
m=json.loads(a.ready_file.read_text());assert m.get('scopeVerified') is True and m['device']=='192.168.10.25:5555' and m['server']=='192.168.10.232' and m['uiContext']=='444556303031' and m['mediaFileId']==20893
assert m['expiresAtMs']-time.time()*1000>65000,'Stale live stage'
rows=[];began=time.monotonic()
for offset in (35,50):
    while time.monotonic()-began<offset:time.sleep(min(1,offset-(time.monotonic()-began)))
    assert m['expiresAtMs']-time.time()*1000>5000
    ui=C.request(cfg,'/v1/control',dict(action='ui.list'),mcp=True)
    assert ui.get('contexts')==['444556303031'],'Foreign client context; refuse collection'
    with urlopen('http://192.168.10.232:31910/v1/capabilities',timeout=4) as response:assert json.load(response)['mimDirect']['activeSessions']==1
    with C.ssh(cfg) as ssh:
        value=json.loads(C.remote(ssh,'docker inspect '+shlex.quote(cfg['unraid_container_name'])))[0]
        mounts=[v['Source'] for v in value['Mounts'] if v['Destination']=='/opt/sagetv'];assert len(mounts)==1 and mounts[0].startswith('/mnt/user/appdata/')
        base=mounts[0]+'/server/plugins/SageTVFFmpegPlugin/runtime/cache/direct-media'
        with ssh.open_sftp() as sftp:
            dirs=[v for v in sftp.listdir_attr(base) if re.fullmatch('[0-9a-f]{64}',v.filename) and stat.S_ISDIR(v.st_mode)]
            active=[]
            for d in dirs:
                segments=[v for v in sftp.listdir_attr(base+'/'+d.filename) if re.fullmatch(r'seg_[0-9]{6}\.ts',v.filename) and stat.S_ISREG(v.st_mode)]
                if segments:active.append((d,sorted(segments,key=lambda x:x.filename)))
            assert len(active)==1 and len(active[0][1])>=2
            directory,segments=active[0];chosen=segments[-2]
            assert 0<chosen.st_size<=4*1024*1024
            with sftp.open(base+'/'+directory.filename+'/'+chosen.filename,'rb') as source:data=source.read(4*1024*1024+1)
            assert len(data)==chosen.st_size
    target=a.output_dir/(a.ready_file.name.removesuffix('-hevc-motion-live-stage.json')+'-producer-'+str(offset)+'-'+chosen.filename)
    with target.open('xb') as output:output.write(data)
    rows.append(dict(offsetSeconds=offset,artifact=target.name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
report=a.output_dir/(a.ready_file.stem+'-producer-evidence.json')
report.write_text(json.dumps(dict(readOnly=True,scopeVerified=True,rows=rows),indent=2)+'\n');print(json.dumps(rows))
