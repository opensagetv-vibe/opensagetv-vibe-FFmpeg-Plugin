#!/usr/bin/env python3
"""MIM-OUTPUT-001: isolated production API output/policy checks on stock232.

Uses a canonical completed fixture. No tuner, Core/INI edits or publication.
Sessions are owned in memory and always torn down; reports exclude handles.
"""
import argparse,hashlib,importlib.util,json,shlex,time,subprocess
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import urlencode,urljoin

ROOT=Path(__file__).resolve().parents[1]
FIXTURE='/var/media/OpenSageTV_Vibe_Tests/VibeCaptionTest-1080i-CEA608-90s-2s.ts'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--run-stock232',action='store_true')
    parser.add_argument('--probe-source',help='Exact indexed completed TS for affected Source/Auto probe-policy checks')
    parser.add_argument('--inspect-source',help='Bounded actual owned-segment codec/profile investigation only')
    args=parser.parse_args()
    if not args.run_stock232:raise SystemExit('Explicit --run-stock232 required')
    web=ROOT.parent/'opensagetv-vibe-WebClient-Plugin'
    if not web.is_dir():web=ROOT.parent/'web-client-plugin'
    spec=importlib.util.spec_from_file_location('commission',web/'scripts/commission-mobile.py')
    C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C);cfg=C.configuration()
    if cfg['address']!='192.168.10.232' or C.activity(cfg).get('safeToRestart') is not True:raise RuntimeError('Independently idle stock232 required')
    task='MIM-PROBE-001' if args.probe_source or args.inspect_source else 'MIM-OUTPUT-001'
    rows=[];tokens=set();report={'task':task,'server':'stock232','passed':False,'physicalPlayback':False}
    out=ROOT/'artifacts/active'/task;out.mkdir(parents=True,exist_ok=True)
    stamp=time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())
    def request(path,values=None,expected=200):
        uri='http://192.168.10.232:31910'+path+('?' + urlencode(values) if values else '')
        try:
            with urlopen(Request(uri,data=b'' if values is not None else None),timeout=25) as r:code=r.status;body=json.load(r)
        except HTTPError as error:code=error.code;body=json.loads(error.read(4096))
        if code!=expected:raise AssertionError('Unexpected output API HTTP '+str(code)+': '+str(body.get('error',''))[:120])
        return body
    def hashes():
        with C.ssh(cfg) as ssh:
            container=shlex.quote(cfg['unraid_container_name'])
            text=C.remote(ssh,'docker exec '+container+' sha256sum /opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffmpeg.real.ini /opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffmpeg.real /opt/sagetv/server/plugins/SageTVFFmpegPlugin/runtime/ffprobe')
            stock=C.remote(ssh,'docker exec -w /opt/sagetv/server '+container+' sha256sum -c /usr/local/share/sagetv-stock/protected.sha256')
            assert stock.count(': OK')==20
            return hashlib.sha256(text.encode()).hexdigest()
    def reserve(**changes):
        values=dict(source=FIXTURE,mode='transcode',deinterlace='off',videoKbps=2000,outputRevision=1,encoderPolicy='software',resolution='CIF',frameRate='24',audioCodec='aac',audioKbps='96',audioChannels='2',container='mpegts',remux='needed')
        values.update(changes);reply=request('/v1/direct/start',values);tokens.add(reply['sessionToken']);return reply
    def stop(reply):
        token=reply['sessionToken'];request('/v1/direct/teardown',dict(token=token));tokens.remove(token)
    def actual_segment(reply):
        playlist='http://192.168.10.232:31910'+reply['mediaUrl']
        with urlopen(playlist,timeout=15) as stream:lines=stream.read(1024*1024).decode().splitlines()
        segment=next(line for line in lines if line and not line.startswith('#'))
        uri=urljoin(playlist,segment)
        assert uri.startswith('http://192.168.10.232:31910/v1/direct/media/'+reply['sessionToken']+'/')
        with urlopen(uri,timeout=15) as stream:payload=stream.read(16*1024*1024)
        probe=subprocess.run(['/project/output/linux-x64/ffprobe','-v','error','-show_streams','-of','json','pipe:0'],input=payload,capture_output=True,check=True,timeout=15)
        return json.loads(probe.stdout)['streams'],payload
    def prove(reply,hardware):
        value=request('/v1/direct/status?token='+reply['sessionToken'])
        execution=value['execution'];effective=value['effectiveOutput']
        assert execution['state'] not in ('pending','unknown') and execution['hardwareEncode'] is hardware
        assert effective['observed'] is True and effective['videoCodec']=='h264'
        assert int(effective['width'])<=352 and int(effective['height'])<=288
        assert effective['fps']=='24/1' and effective['audioCodec']=='aac' and int(effective['audioChannels'])==2
        return dict(execution=execution,effectiveOutput=effective,outputFallbackReason=value.get('outputFallbackReason'))
    before=hashes()
    try:
        caps=request('/v1/capabilities')['mimDirect'];assert caps['activeSessions']==0
        assert caps['outputSettingsRevision']==1 and caps['encoderPolicyRevision']==1
        if args.inspect_source:
            assert args.inspect_source.startswith('/var/media/') and args.inspect_source.endswith('.ts')
            media=C.request(cfg,'/v1/control',dict(action='media.resolve_exact_path',path=args.inspect_source),mcp=True);assert media.get('mediaFileId')
            reply=reserve(source=args.inspect_source,encoderPolicy='server',resolution='SOURCE',frameRate='SOURCE',deinterlace='auto',videoKbps=4000,audioKbps=128,audioChannels='')
            streams,payload=actual_segment(reply)
            observed=[{k:x.get(k) for k in ('codec_type','codec_name','profile','level','pix_fmt','width','height','r_frame_rate','avg_frame_rate','field_order','sample_rate','channels','channel_layout')} for x in streams]
            audio_configs=[]
            for index,_ in enumerate(x for x in streams if x['codec_type']=='audio'):
                audio=subprocess.run(['/project/output/linux-x64/ffmpeg.real','-v','error','-i','pipe:0','-map','0:a:'+str(index),'-c:a','copy','-f','adts','pipe:1'],input=payload,capture_output=True,check=True,timeout=15).stdout
                assert len(audio)>7 and audio[0]==255 and audio[1]&240==240
                audio_configs.append(dict(audioIndex=index,adtsChannelConfiguration=((audio[2]&1)<<2)|(audio[3]>>6)))
            decode=subprocess.run(['/project/output/linux-x64/ffmpeg.real','-v','error','-i','pipe:0','-map','0:v:0','-frames:v','20','-f','null','-'],input=payload,capture_output=True,timeout=20)
            rows.append(dict(gate='actual-owned-source-segment-inspection',passed=decode.returncode==0,streams=observed,adts=audio_configs,softwareDecodeExit=decode.returncode))
            report['passed']=decode.returncode==0;stop(reply);return
        if args.probe_source:
            if not args.probe_source.startswith('/var/media/') or not args.probe_source.endswith('.ts'):raise RuntimeError('Exact test media TS required')
            media=C.request(cfg,'/v1/control',dict(action='media.resolve_exact_path',path=args.probe_source),mcp=True)
            assert media.get('mediaFileId'),'Indexed completed media required'
            for policy in ('legacy','server','prefer_hardware','software','hardware_only'):
                query=dict(source=args.probe_source,mode='transcode',deinterlace='auto',videoKbps=4000,hlsProfile='responsive')
                if policy!='legacy':query.update(outputRevision=1,encoderPolicy=policy,videoCodec='auto',resolution='SOURCE',frameRate='SOURCE',audioCodec='aac',audioKbps=128,audioChannels='',container='mpegts',remux='off')
                reply=request('/v1/direct/start',query);tokens.add(reply['sessionToken'])
                deadline=time.monotonic()+12
                while True:
                    status=request('/v1/direct/status?token='+reply['sessionToken']);effective=status['effectiveOutput'];execution=status['execution']
                    if policy=='legacy' or effective['observed'] or time.monotonic()>=deadline:break
                    time.sleep(.5)
                if policy=='legacy':
                    # Legacy deliberately has no new effective-output contract.
                    # Inspect this owned producer's actual first TS, not its request.
                    streams,_=actual_segment(reply)
                    video=next(x for x in streams if x['codec_type']=='video')
                    effective=dict(observed=True,videoCodec=video['codec_name'],width=video['width'],height=video['height'],fps=video['r_frame_rate'])
                assert effective['observed'] and effective['videoCodec']=='h264',json.dumps(dict(effective=effective,execution=execution))
                a,b=map(int,effective['fps'].split('/'));assert 10<=a/b<=120,'Invalid discovered cadence'
                assert int(effective['width'])==1920 and int(effective['height'])==1080,'Source dimensions not preserved'
                assert execution['hardwareEncode'] is (policy!='software'),'Requested encoder policy not honored'
                rows.append(dict(gate='source-auto-'+policy,passed=True,execution=execution,effectiveOutput=effective))
                print('PASS source-auto-'+policy,flush=True);stop(reply)
        software=reserve();rows.append(dict(gate='software-output',passed=True,**prove(software,False)))
        hardware=reserve(encoderPolicy='hardware_only');rows.append(dict(gate='hardware-only-output',passed=True,**prove(hardware,True)))
        # A concurrent hardware request must not change the first software job.
        assert prove(software,False)['execution']['hardwareEncode'] is False
        rows.append(dict(gate='concurrent-policy-isolation',passed=True))
        stop(hardware)
        restarted=request('/v1/direct/restart',dict(token=software['sessionToken'],startMs=20000,deinterlace='off'))
        tokens.remove(software['sessionToken']);tokens.add(restarted['sessionToken'])
        assert restarted['outputSettings']==software['outputSettings']
        rows.append(dict(gate='seek-preserves-output-policy',passed=True,**prove(restarted,False)));stop(restarted)
        source=reserve(audioCodec='source',audioKbps='source',audioChannels='')
        assert source['effectiveOutput']['audioCodec']=='ac3'
        rows.append(dict(gate='source-audio-codec',passed=True,effectiveOutput=source['effectiveOutput'],fallback=source.get('outputFallbackReason')));stop(source)
        hevc=reserve(videoCodec='hevc')
        assert hevc['effectiveOutput']['videoCodec']=='hevc'
        rows.append(dict(gate='software-hevc-output',passed=True,effectiveOutput=hevc['effectiveOutput']));stop(hevc)
        for values in (dict(container='matroska'),dict(container='dvd'),dict(encoderPolicy='invalid')):
            query=dict(source=FIXTURE,mode='transcode',outputRevision=1);query.update(values)
            request('/v1/direct/start',query,expected=400)
        rows.append(dict(gate='unsupported-containers-and-policy-refuse',passed=True))
        # Omitted revision/options keep the pre-existing Direct contract.
        legacy=request('/v1/direct/start',dict(source=FIXTURE,mode='copy',deinterlace='off'));tokens.add(legacy['sessionToken'])
        assert legacy['outputSettings']['audioCodec']=='ac3' and legacy['mode']=='copy';stop(legacy)
        rows.append(dict(gate='legacy-omitted-options-copy',passed=True))
        report['passed']=True
    finally:
        for token in list(tokens):
            request('/v1/direct/teardown',dict(token=token));tokens.remove(token)
        report['rows']=rows;report['activeSessionsAfter']=request('/v1/capabilities')['mimDirect']['activeSessions']
        report['protectedStockFilesVerified']=20;report['iniAndQualifiedEnginesUnchanged']=hashes()==before
        report['passed']=report['passed'] and report['activeSessionsAfter']==0 and report['iniAndQualifiedEnginesUnchanged']
        (out/(stamp+'-output-api.json')).write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report),flush=True)

if __name__=='__main__':main()
