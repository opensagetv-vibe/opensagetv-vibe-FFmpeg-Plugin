#!/usr/bin/env python3
"""SETTINGS-005 real, owned QSV/VAAPI output/seek/quality checks on stock232.

No Android/browser rendering claim; existing public API, no tuner or INI edit.
"""
import argparse,hashlib,importlib.util,json,shlex,subprocess,time
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.parse import urlencode,urljoin
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--run-stock232',action='store_true')
args=parser.parse_args()
if not args.run_stock232:raise SystemExit('Explicit --run-stock232 required')
web=ROOT.parent/'opensagetv-vibe-WebClient-Plugin'
if not web.is_dir():web=ROOT.parent/'web-client-plugin'
spec=importlib.util.spec_from_file_location('commission',web/'scripts/commission-mobile.py')
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C);cfg=C.configuration()
if cfg['address']!='192.168.10.232' or C.activity(cfg).get('safeToRestart') is not True:raise SystemExit('Independent idle stock232 required')
origin='http://192.168.10.232:31910';tokens=set();rows=[]
report={'task':'SETTINGS-005','physicalPlayback':False,'passed':False,'server':'stock232'}
out=ROOT/'artifacts/active/SETTINGS-005';out.mkdir(parents=True,exist_ok=True)
stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())
def request(path,values=None):
    uri=origin+path+('?' +urlencode(values) if values is not None else '')
    with urlopen(Request(uri,data=b'' if values is not None else None),timeout=30) as r:return json.load(r)
def guard():
    with C.ssh(cfg) as ssh:
        name=shlex.quote(cfg['unraid_container_name'])
        stock=C.remote(ssh,'docker exec -w /opt/sagetv/server '+name+' sha256sum -c /usr/local/share/sagetv-stock/protected.sha256')
        assert stock.count(': OK')==20
        values=C.remote(ssh,'docker exec '+name+' sha256sum /opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffmpeg.real.ini /opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffmpeg.real /opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffprobe')
        return hashlib.sha256(values.encode()).hexdigest()
def prove(reply,backend):
    execution=reply.get('execution',{});options=reply['outputSettings']
    assert options['hardwareBackend']==backend and execution.get('backend')==backend
    assert execution.get('hardwareEncode') is True and execution.get('encoder')=='h264_'+backend,execution
    playlist=origin+reply['mediaUrl']
    with urlopen(playlist,timeout=15) as r:lines=r.read(1024*1024).decode().splitlines()
    segment=next(v for v in lines if v and not v.startswith('#'))
    uri=urljoin(playlist,segment)
    assert uri.startswith(origin+'/v1/direct/media/'+reply['sessionToken']+'/')
    with urlopen(uri,timeout=15) as r:payload=r.read(16*1024*1024)
    probe=subprocess.run(['/project/output/linux-x64/ffprobe','-v','error','-show_streams','-of','json','pipe:0'],input=payload,capture_output=True,check=True,timeout=15)
    video=next(v for v in json.loads(probe.stdout)['streams'] if v['codec_type']=='video')
    assert video['codec_name']=='h264' and video['width']==1920 and video['height']==1080
    a,b=map(int,video['r_frame_rate'].split('/'));assert 10<a/b<120
    decode=subprocess.run(['/project/output/linux-x64/ffmpeg.real','-v','error','-i','pipe:0','-map','0:v:0','-frames:v','20','-f','null','-'],input=payload,capture_output=True,timeout=20)
    assert decode.returncode==0,decode.stderr.decode(errors='replace')[:160]
    return {'execution':execution,'outputSettings':options,'video':{k:video.get(k) for k in ('codec_name','width','height','r_frame_rate')},'decodeExit':0}
before=guard()
try:
    assert request('/v1/capabilities')['mimDirect']['hardwareBackendRevision']==1
    for backend in ('qsv','vaapi'):
        for profile in ('legacy','responsive'):
            source='/var/media/tv/FacetheNation-66420956-0.ts'
            reply=request('/v1/direct/start',dict(source=source,mode='transcode',startMs=153090,deinterlace='auto',videoKbps=4000,hlsProfile=profile,outputRevision=1,encoderPolicy='hardware_only',hardwareBackend=backend,videoCodec='h264',resolution='SOURCE',frameRate='SOURCE',audioCodec='aac',audioKbps=128,audioChannels='2',container='mpegts',remux='needed'))
            tokens.add(reply['sessionToken']);options=reply['outputSettings']
            rows.append(dict(gate=backend+'-'+profile+'-start',passed=True,**prove(reply,backend)))
            seek=request('/v1/direct/restart',dict(token=reply['sessionToken'],startMs=419527,deinterlace='auto'))
            tokens.discard(reply['sessionToken']);tokens.add(seek['sessionToken'])
            assert seek['outputSettings']==options
            rows.append(dict(gate=backend+'-'+profile+'-seek',passed=True,**prove(seek,backend)))
            replacement=request('/v1/direct/quality/prepare',dict(token=seek['sessionToken'],startMs=430000,videoKbps=2800))
            tokens.add(replacement['sessionToken']);assert replacement['outputSettings']==options
            rows.append(dict(gate=backend+'-'+profile+'-quality-prepare',passed=True,**prove(replacement,backend)))
            assert request('/v1/direct/quality/abort',dict(token=seek['sessionToken'],replacementToken=replacement['sessionToken']))['ok']
            tokens.remove(replacement['sessionToken'])
            request('/v1/direct/teardown',dict(token=seek['sessionToken']));tokens.remove(seek['sessionToken'])
            print('PASS '+backend+' '+profile+' actual output / seek / quality pin preservation',flush=True)
    report['passed']=True
finally:
    for token in list(tokens):
        request('/v1/direct/teardown',dict(token=token));tokens.remove(token)
    report.update(rows=rows,activeSessionsAfter=request('/v1/capabilities')['mimDirect']['activeSessions'],protectedStockFiles=20,iniAndEnginesUnchanged=guard()==before)
    report['passed']=report['passed'] and report['activeSessionsAfter']==0 and report['iniAndEnginesUnchanged']
    (out/(stamp+'-hardware-api.json')).write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report),flush=True)
raise SystemExit(0 if report['passed'] else 1)
