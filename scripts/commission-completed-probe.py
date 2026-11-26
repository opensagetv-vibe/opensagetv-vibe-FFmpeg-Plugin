#!/usr/bin/env python3
"""REMOTE-002: explicit exact-stock232 duration-cache API gate.

Uses an existing canonical fixture, production Direct Copy and read-only MCP
activity. Never edits media/settings/Core or reports API receipts as playback
proof. Opaque handles stay in memory; every owned producer retires in finally.
"""
import argparse
import importlib.util
import json
import time
from pathlib import Path
from urllib.parse import urlencode

ROOT=Path(__file__).resolve().parents[1]
FIXTURE='/var/media/OpenSageTV_Vibe_Tests/VibeCaptionTest-1080i-CEA608-90s-2s.ts'

def run():
    parser=argparse.ArgumentParser()
    parser.add_argument('--run-stock232',action='store_true')
    args=parser.parse_args()
    if not args.run_stock232:raise SystemExit('Explicit --run-stock232 required')
    spec=importlib.util.spec_from_file_location('commission',ROOT.parent/'opensagetv-vibe-WebClient-Plugin/scripts/commission-mobile.py')
    C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C)
    spec=importlib.util.spec_from_file_location('quality_gate',ROOT/'scripts/commission-direct-quality.py')
    Q=importlib.util.module_from_spec(spec);spec.loader.exec_module(Q)
    cfg=C.configuration()
    if cfg['address']!='192.168.10.232':raise RuntimeError('Exact stock232 required')
    before=C.activity(cfg)
    if before.get('safeToRestart') is not True:raise RuntimeError('Independent idle guard refused')
    api='http://192.168.10.232:31910';tokens=[];rows=[]
    report={'task':'REMOTE-002','server':'.232 stock','physicalPlayback':False,'passed':False}
    out=ROOT/'artifacts/active/REMOTE-002';out.mkdir(parents=True,exist_ok=True)
    stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())
    def request(path,values=None,allowed=(200,)):
        code,body=Q.request(api,path,values)
        if code not in allowed:raise AssertionError('Unexpected Direct HTTP status '+str(code))
        return json.loads(body)
    def stop(token):
        request('/v1/direct/teardown?'+urlencode({'token':token}),{},(200,404))
        tokens.remove(token)
    try:
        caps=request('/v1/capabilities')['mimDirect']
        assert caps.get('sourceProbeCacheRevision')==1 and caps.get('activeSessions')==0
        for index,target in enumerate((42000,42000,999999,0)):
            started=time.monotonic()
            value=request('/v1/direct/start?'+urlencode({'source':FIXTURE,'mode':'copy','startMs':target,'active':'true'}),{})
            token=value['sessionToken'];tokens.append(token)
            disposition=value.get('durationProbeCache')
            assert value.get('active') is False and value.get('mode')=='copy'
            assert value['startMs']==target if target<=42000 else 0<value['startMs']<90000
            if index==0:assert disposition in ('miss','hit')
            elif index in (1,2):assert disposition=='hit'
            else:assert disposition=='not_needed'
            assert request('/v1/capabilities')['mimDirect']['activeSessions']==1
            rows.append({'requestedMs':target,'acceptedMs':value['startMs'],'cache':disposition,
                         'apiElapsedMs':round((time.monotonic()-started)*1000),'passed':True})
            stop(token)
            print('PASS cache '+str(disposition)+' requested'+str(target),flush=True)
        report['passed']=True
    except BaseException as failure:
        report['failureType']=type(failure).__name__
        raise
    finally:
        for token in list(tokens):stop(token)
        report['rows']=rows
        report['finalActiveSessions']=request('/v1/capabilities')['mimDirect']['activeSessions']
        activity=C.activity(cfg)
        report['finalActivity']={k:activity.get(k) for k in ('timestampMs','recordingCount','uiContextCount','connectedClientCount','safeToRestart')}
        with C.ssh(cfg) as ssh:
            stock=C.remote(ssh,'docker exec -w /opt/sagetv/server sagetv-vibe-server-u26-gpu-j11 sha256sum -c /usr/local/share/sagetv-stock/protected.sha256')
            report['protectedStockFilesVerified']=stock.count(': OK')
        if report['finalActiveSessions']!=0 or activity.get('safeToRestart') is not True or report['protectedStockFilesVerified']!=20:
            report['passed']=False
        (out/(stamp+'-completed-probe.json')).write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({'passed':report['passed'],'rows':len(rows),'finalActiveSessions':report['finalActiveSessions'],'protectedStockFilesVerified':report['protectedStockFilesVerified']}),flush=True)

if __name__=='__main__':run()
