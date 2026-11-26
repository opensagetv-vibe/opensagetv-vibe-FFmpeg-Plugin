#!/usr/bin/env python3
"""Explicit stock232 REMOTE-001 provider gate, not a physical player claim.

Uses the production Direct API and existing read-only MCP activity guard.
No Web Client runtime/control dependency, arbitrary paths or producer replay.
Opaque handles/configuration secrets stay in memory, never in the report.
"""
import argparse
import importlib.util
import json
import os
import time
import tomllib
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request,urlopen

ROOT=Path(__file__).resolve().parents[1]
FIXTURE='/var/media/OpenSageTV_Vibe_Tests/VibeCaptionTest-1080i-CEA608-90s-2s.ts'

def overlap_filter_proof(handles):
    """Independent read-only child-command witness, never publish raw argv or
    opaque output paths. Reuse commissioned pinned SSH, not a production Web
    dependency. Only this gate's exact output-directory handles are selected."""
    module_path=ROOT.parent/'opensagetv-vibe-WebClient-Plugin/scripts/commission-mobile.py'
    spec=importlib.util.spec_from_file_location('stock_commission',module_path)
    commission=importlib.util.module_from_spec(spec);spec.loader.exec_module(commission)
    cfg=commission.configuration()
    if cfg['unraid_container_name']!='sagetv-vibe-server-u26-gpu-j11':raise RuntimeError('Wrong process-witness target')
    with commission.ssh(cfg) as ssh:
        raw=commission.remote(ssh,'docker exec sagetv-vibe-server-u26-gpu-j11 ps -eo pid,ppid,args')
    rows=[line.strip().split(None,2) for line in raw.splitlines()[1:] if line.strip()]
    result=[]
    for handle in handles:
        owned=[row for row in rows if len(row)==3 and '/'+handle+'/' in row[2]]
        parents=[row for row in owned if '-sagetvdirect' in row[2] and 'ffmpeg_MIM' in row[2]]
        assert len(parents)==1,'Exact wrapper process not unique'
        children=[row for row in owned if row[1]==parents[0][0] and 'ffmpeg.real' in row[2]]
        assert len(children)==1,'Exact encoder child not unique'
        args=children[0][2]
        vaapi='-hwaccel vaapi' in args and '-hwaccel_output_format vaapi' in args and 'deinterlace_vaapi' in args and 'h264_vaapi' in args
        qsv='-hwaccel qsv' in args and '-hwaccel_output_format qsv' in args and ('vpp_qsv' in args or 'deinterlace_qsv' in args) and 'h264_qsv' in args
        assert (vaapi or qsv) and 'hwdownload' not in args,'Full hardware decode/filter/encode not independently witnessed'
        result.append({'backend':'vaapi' if vaapi else 'qsv','exactWrapperAndChild':True,'hardwareDecode':True,'hardwareDeinterlace':True,'hardwareEncode':True})
    return result

def handoff_gate(post,json_request,record,tokens):
    """Provider lifecycle gate. Commit ACK is synthetic, NOT a decoded frame.

    Physical client acceptance is separately required. Never publish these
    receipts as a seamless/visible playback or per-stage hardware filter pass.
    """
    def start():
        original=post('start',source=FIXTURE,mode='transcode',startMs=0,deinterlace='on')
        tokens.append(original['sessionToken']);return original
    completed=post('start',source=FIXTURE,mode='transcode',startMs=42000,deinterlace='on',active='true')
    tokens.append(completed['sessionToken'])
    assert completed['active'] is False and completed['activeFileApplied'] is False
    assert completed['startMs']==42000
    record('public-completed-writer-truth-corrects-stale-active-hint',startMs=42000,
           active=False,contentPositionRequiresIndependentPicture=True)
    post('teardown',token=tokens.pop())
    original=start();old=original['sessionToken']
    candidate=post('quality/prepare',token=old,startMs=30000,videoKbps=1000)
    new=candidate['sessionToken'];tokens.append(new)
    assert new!=old and candidate['videoTargetKbps']==1000 and candidate['deinterlace']=='on'
    assert candidate['startMs']==30000
    assert json_request('/v1/direct/status?'+urlencode({'token':old}))['sessionToken']==old
    assert json_request('/v1/capabilities')['mimDirect']['activeSessions']==2
    record('handoff-ready-preserves-both-leases',videoTargetKbps=1000,startMs=30000)
    # Exact process identity is enforced inside each Session before these
    # sanitized execution fields can be returned. Same-source latest-job
    # matching is not sufficient when two producers deliberately overlap.
    proof=[]
    for handle in (old,new):
        deadline=time.monotonic()+10
        while True:
            execution=json_request('/v1/direct/status?'+urlencode({'token':handle})).get('execution',{})
            if execution.get('path')!='unknown':break
            if time.monotonic()>=deadline:raise AssertionError('Exact producer execution unavailable')
            time.sleep(.25)
        assert execution.get('hardwareDecode') is True and execution.get('hardwareEncode') is True
        proof.append(execution)
    filters=overlap_filter_proof((old,new))
    record('independently-owned-overlap-decode-filter-encode',execution=proof,independentChildCommands=filters,hardwareFilterIndependentlyProven=True)
    json_request('/v1/direct/quality/prepare?'+urlencode({'token':old,'startMs':31000,'videoKbps':500}),{},409)
    json_request('/v1/direct/quality/commit?'+urlencode({'token':old,'replacementToken':old,'presentedMs':30001}),{},409)
    record('duplicate-and-wrong-lineage-refused')
    result=post('quality/commit',token=old,replacementToken=new,presentedMs=30001)
    assert result['sessionToken']==new
    post('quality/commit',token=old,replacementToken=new,presentedMs=30001)
    json_request('/v1/direct/status?'+urlencode({'token':old}),expected=404)
    assert json_request('/v1/capabilities')['mimDirect']['activeSessions']==1
    record('synthetic-client-commit-idempotent-old-owner-retired',physicalPresentation=False)
    for handle in list(tokens):post('teardown',token=handle)
    tokens.clear()

    original=start();old=original['sessionToken']
    candidate=post('quality/prepare',token=old,startMs=10000,videoKbps=1000)
    new=candidate['sessionToken'];tokens.append(new)
    post('quality/abort',token=old,replacementToken=new)
    tokens.remove(new)
    json_request('/v1/direct/status?'+urlencode({'token':new}),expected=404)
    assert json_request('/v1/direct/status?'+urlencode({'token':old}))['videoTargetKbps']==6000
    assert json_request('/v1/capabilities')['mimDirect']['activeSessions']==1
    record('abort-preserves-original-target')
    post('teardown',token=old);tokens.clear()

    original=start();old=original['sessionToken']
    candidate=post('quality/prepare',token=old,startMs=10000,videoKbps=1000)
    new=candidate['sessionToken'];tokens.append(new)
    sought=post('restart',token=old,startMs=42000,deinterlace='on')
    tokens.remove(new)
    tokens.append(sought['sessionToken'])
    assert sought['videoTargetKbps']==6000 and sought['startMs']==42000
    json_request('/v1/direct/status?'+urlencode({'token':new}),expected=404)
    json_request('/v1/direct/quality/commit?'+urlencode({'token':old,'replacementToken':new,'presentedMs':10001}),{},409)
    record('seek-supersedes-uncommitted-quality')
    for handle in list(tokens):post('teardown',token=handle)
    tokens.clear()
    assert json_request('/v1/capabilities')['mimDirect'].get('qualitySeekRevision')==1
    for phase in ('pending','committed'):
        original=start();old=original['sessionToken']
        candidate=post('quality/prepare',token=old,startMs=10000,videoKbps=1000)
        new=candidate['sessionToken'];tokens.append(new)
        if phase=='committed':post('quality/commit',token=old,replacementToken=new,presentedMs=10001)
        json_request('/v1/direct/quality/seek?'+urlencode({'token':old,'replacementToken':'0'*64,'startMs':42000}),{},409)
        sought=post('quality/seek',token=old,replacementToken=new,startMs=42000,deinterlace='on')
        tokens.append(sought['sessionToken'])
        assert sought['startMs']==42000 and sought['videoTargetKbps']==(1000 if phase=='committed' else 6000)
        assert json_request('/v1/capabilities')['mimDirect']['activeSessions']==1
        record('atomic-quality-seek-'+phase,startMs=42000,videoTargetKbps=sought['videoTargetKbps'],physicalPresentation=False)
        json_request('/v1/direct/teardown?'+urlencode({'token':new}),{},200 if phase=='committed' else 404)
        tokens.remove(new)
        for handle in list(tokens):post('teardown',token=handle)
        tokens.clear()

    caps=json_request('/v1/capabilities')['mimDirect']
    assert caps.get('qualityStartRevision')==1 and caps.get('qualityRecoveryRevision')==1
    original=post('start',source=FIXTURE,mode='transcode',startMs=0,deinterlace='on',videoKbps=1000)
    old=original['sessionToken'];tokens.append(old)
    assert original['videoTargetKbps']==1000 and original['videoCeilingKbps']==1000
    attempt_began=time.monotonic()
    candidate=post('quality/prepare',token=old,startMs=10000,videoKbps=700)
    new=candidate['sessionToken'];tokens.append(new)
    post('quality/commit',token=old,replacementToken=new,presentedMs=10001)
    assert candidate['videoCeilingKbps']==1000
    for target in (1001,900):
        json_request('/v1/direct/quality/prepare?'+urlencode({'token':new,'startMs':20000,'videoKbps':target,'recovery':'true'}),{},400)
    record('initial-ceiling-and-invalid-recovery-refusal',initialKbps=1000,downKbps=700,physicalPresentation=False)
    while time.monotonic()-attempt_began<61:
        print('WAIT real provider recovery cooldown',flush=True)
        time.sleep(min(10,61-(time.monotonic()-attempt_began)))
    recovered=post('quality/prepare',token=new,startMs=20000,videoKbps=875,recovery='true')
    recovered_token=recovered['sessionToken'];tokens.append(recovered_token)
    assert recovered['videoTargetKbps']==875 and recovered['videoCeilingKbps']==1000
    assert json_request('/v1/capabilities')['mimDirect']['activeSessions']==2
    post('quality/commit',token=new,replacementToken=recovered_token,presentedMs=20001)
    assert json_request('/v1/capabilities')['mimDirect']['activeSessions']==1
    record('synthetic-recovery-after-real-cooldown-retains-ceiling',videoTargetKbps=875,videoCeilingKbps=1000,physicalPresentation=False)
    for handle in list(tokens):post('teardown',token=handle)
    tokens.clear()

def request(base,path,values=None,token=None):
    headers={'Accept':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    data=urlencode(values).encode() if values is not None else None
    if data is not None:headers['Content-Type']='application/x-www-form-urlencoded'
    try:
        with urlopen(Request(base+path,data=data,headers=headers),timeout=30) as response:
            body=response.read(262145)
            if len(body)>262144:raise RuntimeError('Response exceeds bound')
            return response.status,body
    except HTTPError as error:return error.code,error.read(4096)

def run():
    parser=argparse.ArgumentParser();parser.add_argument('--run-stock232',action='store_true')
    parser.add_argument('--handoff',action='store_true',help='Optional producer handoff contract, synthetic ACK only')
    parser.add_argument('--overlap-only',action='store_true',help='Focused independent hardware decode/filter/encode witness; no commit ACK')
    args=parser.parse_args()
    if not args.run_stock232:raise SystemExit('Explicit --run-stock232 required')
    cfg=tomllib.loads((ROOT.parent/'opensagetv-vibe-android-client/config/firetv.toml').read_text())['servers']['vibe']
    if cfg['address']!='192.168.10.232':raise RuntimeError('This gate is restricted to commissioned232')
    api='http://'+cfg['address']+':31910'
    status,body=request(cfg['core_mcp_base_url'].rstrip('/'),'/v1/control',{'action':'server.activity'},cfg['core_mcp_token'])
    activity=json.loads(body)
    if status!=200 or not activity.get('ok') or activity.get('safeToRestart') is not True:
        raise RuntimeError('Independent recording/client activity is not idle')
    out=Path(os.environ.get('SAGETV_ARTIFACT_DIR',ROOT/'artifacts/active/REMOTE-001')).resolve()
    if not out.is_relative_to((ROOT/'artifacts/active').resolve()):raise RuntimeError('Output outside owning active artifacts')
    out.mkdir(parents=True,exist_ok=True)
    rows=[];tokens=[];passed=False
    def json_request(path,values=None,expected=200):
        code,raw=request(api,path,values)
        if code!=expected:raise AssertionError('Unexpected Direct HTTP status '+str(code))
        return json.loads(raw)
    def post(action,**values):
        path='/v1/direct/'+action+'?'+urlencode(values)
        if action=='teardown':
            # A committed old producer leaves its15s grace during the REAL
            # 60s cooldown.404 then proves it already retired; do not replay
            # another mutation or misreport this as a producer cleanup failure.
            code,raw=request(api,path,{})
            if code not in (200,404):raise AssertionError('Owned teardown status '+str(code))
            return json.loads(raw)
        return json_request(path,{})
    def record(name,**values):rows.append(dict(gate=name,passed=True,**values));print('PASS '+name,flush=True)
    try:
        caps=json_request('/v1/capabilities')['mimDirect']
        assert caps.get('available') and caps.get('sessionQualityRevision')==1 and caps.get('activeSessions')==0
        if args.overlap_only:
            original=post('start',source=FIXTURE,mode='transcode',startMs=0,deinterlace='on');tokens.append(original['sessionToken'])
            candidate=post('quality/prepare',token=tokens[0],startMs=30000,videoKbps=1000);tokens.append(candidate['sessionToken'])
            proof=overlap_filter_proof(tokens)
            record('independent-overlap-hardware-decode-filter-encode',producers=proof,physicalPresentation=False)
            passed=True;return
        if args.handoff:
            assert caps.get('qualityHandoffRevision')==1 and caps.get('maxPendingQualityHandoffs')==1
            assert caps.get('qualityHandoffTimeoutMs')==45000
            handoff_gate(post,json_request,record,tokens)
            passed=True
            return
        original=post('start',source=FIXTURE,mode='transcode',startMs=0,deinterlace='on')
        tokens.append(original['sessionToken'])
        assert original['videoTargetKbps']==6000
        execution=original.get('execution',{})
        # Runtime job publication can trail playlist readiness slightly. Read
        # status only; never restart a producer to obtain a desired status.
        deadline=time.monotonic()+10
        while execution.get('path')=='unknown' and time.monotonic()<deadline:
            time.sleep(.25)
            original=json_request('/v1/direct/status?'+urlencode({'token':tokens[0]}))
            execution=original.get('execution',{})
        record('transcode-start',videoTargetKbps=original['videoTargetKbps'],execution=execution)
        old_playlist=original['mediaUrl']
        lower=post('quality',token=tokens[0],startMs=30000,videoKbps=1000)
        tokens.append(lower['sessionToken'])
        assert lower['videoTargetKbps']==1000 and lower['startMs']==30000 and lower['deinterlace']=='on'
        assert tokens[0]!=tokens[1] and lower['qualityReductions']==1
        code,body=request(api,old_playlist)
        assert code==200 and b'#EXTM3U' in body
        json_request('/v1/direct/status?'+urlencode({'token':tokens[0]}),expected=404)
        record('single-session-quality-and-published-handoff',videoTargetKbps=1000,startMs=lower['startMs'],execution=lower.get('execution',{}))
        for label,handle,kbps,expected in [('retired-token',tokens[0],500,400),('cooldown',tokens[1],500,409),('upshift-not-supported-by-revision1',tokens[1],2000,400)]:
            json_request('/v1/direct/quality?'+urlencode({'token':handle,'startMs':31000,'videoKbps':kbps}),{},expected)
            record(label)
        restarted=post('restart',token=tokens[1],startMs=42000,deinterlace='on')
        tokens.append(restarted['sessionToken'])
        assert restarted['videoTargetKbps']==1000 and restarted['qualityReductions']==1 and restarted['startMs']==42000
        record('ordinary-seek-preserves-session-quality',videoTargetKbps=1000,startMs=42000)
        for handle in list(tokens):post('teardown',token=handle)
        tokens.clear()
        copied=post('start',source=FIXTURE,mode='copy',startMs=0,deinterlace='off')
        tokens.append(copied['sessionToken'])
        json_request('/v1/direct/quality?'+urlencode({'token':tokens[0],'startMs':1000,'videoKbps':1000}),{},400)
        record('copy-refuses-quality-encoding')
        passed=True
    finally:
        cleanup=[]
        for handle in tokens:
            try:post('teardown',token=handle)
            except Exception:cleanup.append('owned teardown failed')
        try:
            final=json_request('/v1/capabilities')['mimDirect'].get('activeSessions')
            if final!=0:cleanup.append('Direct sessions remain after gate')
        except Exception:cleanup.append('Final session status unavailable')
        passed=passed and not cleanup
        result={'passed':passed,'server':'.232 stock Linux','physicalPlayer':False,
                'contract':'independent-overlap-hardware' if args.overlap_only else 'handoff1-synthetic-ACK' if args.handoff else 'quality1',
                'fixture':'canonical90s/2s CEA608','rows':rows,'cleanup':cleanup}
        (out/(time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())+('-overlap-hardware.json' if args.overlap_only else '-direct-handoff.json' if args.handoff else '-direct-quality.json'))).write_text(json.dumps(result,indent=2)+'\n')
        if cleanup:raise RuntimeError('; '.join(cleanup))
    if not passed:raise SystemExit(1)

if __name__=='__main__':run()
