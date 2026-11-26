#!/usr/bin/env python3
"""REMOTE-003 exact stock232 preview API/image/expiry commissioning.

No playback stream or private Core controls; uses one fixed existing fixture.
Handles stay in memory. Owned leases retire in finally, including failures.
"""
import argparse
import hashlib
import importlib.util
import json
import shlex
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,urlopen

ROOT=Path(__file__).resolve().parents[1]
FIXTURE='/var/media/OpenSageTV_Vibe_Tests/VibeCaptionTest-1080i-CEA608-90s-2s.ts'

def run():
    parser=argparse.ArgumentParser();parser.add_argument('--run-stock232',action='store_true')
    parser.add_argument('--expiry',action='store_true',help='Real120s preview-only lease expiry; no encode while waiting')
    args=parser.parse_args()
    if not args.run_stock232:raise SystemExit('Explicit --run-stock232 required')
    spec=importlib.util.spec_from_file_location('commission',ROOT.parent/'opensagetv-vibe-WebClient-Plugin/scripts/commission-mobile.py')
    C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C);cfg=C.configuration()
    if cfg['address']!='192.168.10.232' or C.activity(cfg).get('safeToRestart') is not True:
        raise RuntimeError('Exact stock232 independent idle scope required')
    out=ROOT/'artifacts/active/REMOTE-003';out.mkdir(parents=True,exist_ok=True)
    stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime());tokens=[];rows=[]
    report={'task':'REMOTE-003','server':'.232 stock','passed':False,'physicalClient':False,'newPlaybackStreams':0}
    def request(path,values=None,expected=(200,),image=False,method=None):
        uri='http://192.168.10.232:31910'+path
        if values:uri+='?'+urlencode(values)
        began=time.monotonic()
        try:
            with urlopen(Request(uri,data=b'' if method!='GET' else None,method=method or 'POST'),timeout=6) as r:
                body=r.read(65537);code=r.status;headers=r.headers
        except HTTPError as error:
            code=error.code;body=error.read(4096);headers=error.headers
        if code not in expected:raise AssertionError('Unexpected preview HTTP '+str(code))
        if image:
            if len(body)>32768 or body[:2]!=b'\xff\xd8' or body[-2:]!=b'\xff\xd9' or headers.get('Content-Type')!='image/jpeg':
                raise AssertionError('Invalid bounded JPEG response')
            return body,{'bytes':len(body),'positionMs':int(headers.get('X-Vibe-Preview-Position-Ms','-1')),
                         'cached':headers.get('X-Vibe-Preview-Cached')=='true','elapsedMs':round((time.monotonic()-began)*1000),
                         'sha256':hashlib.sha256(body).hexdigest()}
        return json.loads(body)
    def caps():return request('/v1/capabilities',method='GET')['mimDirect']
    def reserve():
        value=request('/v1/previews/reserve',{'source':FIXTURE});tokens.append(value['previewToken']);return value
    def teardown(token):
        request('/v1/previews/teardown',{'previewToken':token},expected=(200,404));tokens.remove(token)
    try:
        initial=caps()
        assert initial.get('sourcePreviewRevision')==1 and initial.get('sourcePreviewLeaseRevision')==1
        assert initial.get('activeSessions')==0 and initial.get('activePreviewLeases')==0
        lease=reserve();token=lease['previewToken'];assert lease['expiresInMs']==120000
        request('/v1/previews/image',{'previewToken':token,'positionMs':42000},expected=(405,),method='GET')
        body,first=request('/v1/previews/image',{'previewToken':token,'positionMs':42000},image=True)
        assert first['positionMs']==40000 and not first['cached']
        (out/(stamp+'-source40.jpg')).write_bytes(body)
        repeated,hit=request('/v1/previews/image',{'previewToken':token,'positionMs':42000},image=True)
        assert hit['cached'] and repeated==body
        rows.append({'gate':'source40-image-cache-and-method','passed':True,'first':first,'hit':hit})
        time.sleep(2.1)
        body,other=request('/v1/previews/image',{'previewToken':token,'positionMs':15000},image=True)
        assert other['positionMs']==10000 and not other['cached'] and body!=repeated
        (out/(stamp+'-source10.jpg')).write_bytes(body)
        assert caps()['activeSessions']==0,'Preview allocated another playback producer'
        rows.append({'gate':'different-source-position-without-playback-stream','passed':True,'image':other})
        # Independent fixture oracle bypasses MIM argument rewriting while
        # retaining the exact installed FFmpeg bytes. No source/output changes.
        # Matching JPEG bytes prove source selection, not physical playback.
        with C.ssh(cfg) as ssh:
            for position,expected_hash in ((40,first['sha256']),(10,other['sha256'])):
                command=['docker','exec','sagetv-vibe-server-u26-gpu-j11','timeout','5',
                         '/opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffmpeg.real',
                         '-hide_banner','-loglevel','error','-ss',str(position),'-i',FIXTURE,
                         '-map','0:v:0','-an','-sn','-threads','1','-frames:v','1',
                         '-vf','scale=192:108','-c:v','mjpeg','-q:v','5','-f','image2pipe','pipe:1']
                _,stdout,stderr=ssh.exec_command(shlex.join(command),timeout=8)
                reference=stdout.read(32769);code=stdout.channel.recv_exit_status()
                assert code==0 and hashlib.sha256(reference).hexdigest()==expected_hash,'Preview differs from independently decoded fixture frame'
        rows.append({'gate':'independent-installed-ffmpeg-fixture40-and10-byte-match','passed':True})
        teardown(token)
        request('/v1/previews/image',{'previewToken':token,'positionMs':42000},expected=(400,))
        rows.append({'gate':'explicit-retirement-refuses-stale-image','passed':True})
        if args.expiry:
            lease=reserve();token=lease['previewToken'];end=time.monotonic()+lease['expiresInMs']/1000+.5
            while time.monotonic()<end:
                print('WAIT real preview-only expiry; remainingSeconds='+str(max(0,round(end-time.monotonic()))),flush=True)
                time.sleep(min(10,max(0,end-time.monotonic())))
            request('/v1/previews/image',{'previewToken':token,'positionMs':42000},expected=(400,))
            assert caps()['activePreviewLeases']==0
            teardown(token)
            rows.append({'gate':'real120s-source-only-lease-expiry','passed':True})
        report['passed']=True
    except BaseException as error:
        report['failureType']=type(error).__name__;raise
    finally:
        for token in list(tokens):teardown(token)
        report['rows']=rows;final=caps()
        report['finalActiveSessions']=final['activeSessions'];report['finalActivePreviewLeases']=final['activePreviewLeases']
        activity=C.activity(cfg);report['finalActivity']={k:activity.get(k) for k in ('timestampMs','recordingCount','uiContextCount','connectedClientCount','safeToRestart')}
        with C.ssh(cfg) as ssh:
            report['protectedStockFilesVerified']=C.remote(ssh,'docker exec -w /opt/sagetv/server sagetv-vibe-server-u26-gpu-j11 sha256sum -c /usr/local/share/sagetv-stock/protected.sha256').count(': OK')
        if report['finalActiveSessions'] or report['finalActivePreviewLeases'] or activity.get('safeToRestart') is not True or report['protectedStockFilesVerified']!=20:
            report['passed']=False
        (out/(stamp+'-scrub-preview.json')).write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({'passed':report['passed'],'rows':len(rows),'activePlayback':report['finalActiveSessions'],'activePreviewLeases':report['finalActivePreviewLeases']}),flush=True)

if __name__=='__main__':run()
