#!/usr/bin/env python3
"""Stock232 developer-catalog input; normal SageTV manager owns installation.
Default remains Java-only. Explicit output-runtime opt-in updates only the
wrapper, byte-checking existing engines/default INI. Never seeds installed
files, rewrites registry state, or replaces protected stock bytes."""
import argparse,copy,hashlib,importlib.util,io,json,shlex,stat,time,uuid,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--with-output-runtime',action='store_true',help='Explicit SETTINGS-003/MIM-OUTPUT-001 wrapper update; engines/default/live INI must remain identical')
args=parser.parse_args()
web=ROOT.parent/'opensagetv-vibe-WebClient-Plugin'
if not web.is_dir():web=ROOT.parent/'web-client-plugin'
spec=importlib.util.spec_from_file_location('commission',web/'scripts/commission-mobile.py')
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C);cfg=C.configuration()
if not C.activity(cfg).get('safeToRestart'):raise SystemExit('Stage requires independently idle stock232')
jar=ROOT/'dist/dev/SageTVFFmpegPlugin.jar';data=jar.read_bytes()
with zipfile.ZipFile(io.BytesIO(data)) as z:
 assert 'org/opensagetv/vibe/ffmpeg/BufferAdaptiveController.class' in z.namelist()
 assert not any(p.startswith('sage/') for p in z.namelist())
package=io.BytesIO()
with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:z.writestr('SageTVFFmpegPlugin.jar',data)
payload=package.getvalue();version='0.1.36.'+time.strftime('%Y%m%d.%H%M%S',time.gmtime())
filename='SageTVFFmpegPlugin-jar-'+version+'.zip'
with C.ssh(cfg) as ssh:
 name=shlex.quote(cfg['unraid_container_name'])
 inspect=json.loads(C.remote(ssh,'docker inspect '+name))[0]
 mounts=[v['Source'] for v in inspect['Mounts'] if v['Destination']=='/opt/sagetv']
 if len(mounts)!=1 or not mounts[0].startswith('/mnt/user/appdata/'):raise RuntimeError('Unexpected appdata')
 protected=C.remote(ssh,'docker exec -w /opt/sagetv/server '+name+' sha256sum -c /usr/local/share/sagetv-stock/protected.sha256')
 directory=mounts[0]+'/server/SageTVPluginsDev.d';catalog=mounts[0]+'/server/SageTVPluginsDev.xml'
 with ssh.open_sftp() as files:
  for p in (mounts[0],mounts[0]+'/server',directory):
   if not stat.S_ISDIR(files.lstat(p).st_mode):raise RuntimeError('Refuse non-directory/reparse staging')
  if not stat.S_ISREG(files.lstat(catalog).st_mode):raise RuntimeError('Refuse non-regular catalog')
  with files.open(catalog,'rb') as f:original=f.read()
  root=ET.fromstring(original,parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))
  entries=[p for p in root.findall('SageTVPlugin') if p.findtext('Identifier')=='SageTVFFmpegPluginLinux']
  if len(entries)!=1:raise RuntimeError('Expected one exact installed Linux descriptor')
  entry=entries[0];before=ET.tostring(entry)
  runtime_package=None;runtime_payload=None;runtime_filename=None
  if args.with_output_runtime:
   runtime_root=Path('/project/output/linux-x64')
   systems=[p for p in entry.findall('Package') if p.findtext('PackageType')=='System']
   if len(systems)!=2 or 'runtime' not in systems[-1].findtext('Location','').lower():raise RuntimeError('Expected exact runtime System package')
   runtime_package=systems[-1];runtime_filename='MIM-output-runtime-'+version+'.zip'
   runtime_dir=mounts[0]+'/server/plugins/SageTVFFmpegPlugin/runtime'
   archive=io.BytesIO()
   with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for item in ('ffmpeg_MIM','ffmpeg.real','ffprobe','ffmpeg.real.ini.default','THIRD_PARTY_NOTICES.md'):
     if item in ('ffmpeg_MIM','ffmpeg.real','ffprobe'):
      payload_item=(runtime_root/item).read_bytes()
      if item!='ffmpeg_MIM':
       with files.open(runtime_dir+'/'+item,'rb') as original_engine:
        if hashlib.sha256(original_engine.read()).digest()!=hashlib.sha256(payload_item).digest():raise RuntimeError('Qualified engine differs; refuse replacement')
     else:
      with files.open(runtime_dir+'/'+item,'rb') as original_file:payload_item=original_file.read()
     info=zipfile.ZipInfo('plugins/SageTVFFmpegPlugin/runtime/'+item,(1980,1,1,0,0,0));info.create_system=3
     info.external_attr=(stat.S_IFREG|(0o755 if item in ('ffmpeg_MIM','ffmpeg.real','ffprobe') else 0o644))<<16;info.compress_type=zipfile.ZIP_DEFLATED
     z.writestr(info,payload_item)
   runtime_payload=archive.getvalue()
  packages=[p for p in entry.findall('Package') if p.findtext('PackageType')=='JAR']
  if len(packages)!=1:raise RuntimeError('Expected one JAR package, refuse broader change')
  unchanged=[ET.tostring(p) for p in entry if p.tag in ('Dependency','Package') and p is not packages[0] and p is not runtime_package]
  entry.find('Version').text=version;packages[0].find('Location').text='file:///opt/sagetv/server/SageTVPluginsDev.d/'+filename
  packages[0].find('MD5').text=hashlib.md5(payload).hexdigest()
  if runtime_package is not None:
   runtime_package.find('Location').text='file:///opt/sagetv/server/SageTVPluginsDev.d/'+runtime_filename
   runtime_package.find('MD5').text=hashlib.md5(runtime_payload).hexdigest()
  if unchanged!=[ET.tostring(p) for p in entry if p.tag in ('Dependency','Package') and p is not packages[0] and p is not runtime_package]:raise RuntimeError('Unrelated package/dependency change refused')
  if not C.activity(cfg).get('safeToRestart'):raise RuntimeError('Activity changed before staging')
  target=directory+'/'+filename
  try:files.lstat(target);raise RuntimeError('Refuse package overwrite')
  except FileNotFoundError:pass
  with files.open(target,'wb') as f:f.write(payload)
  with files.open(target,'rb') as f:
   if hashlib.sha256(f.read()).digest()!=hashlib.sha256(payload).digest():raise RuntimeError('Staged package differs')
  if runtime_payload is not None:
   runtime_target=directory+'/'+runtime_filename
   try:files.lstat(runtime_target);raise RuntimeError('Refuse runtime package overwrite')
   except FileNotFoundError:pass
   with files.open(runtime_target,'wb') as f:f.write(runtime_payload)
   with files.open(runtime_target,'rb') as f:
    if hashlib.sha256(f.read()).digest()!=hashlib.sha256(runtime_payload).digest():raise RuntimeError('Staged runtime package differs')
  temporary=catalog+'.adaptive-'+uuid.uuid4().hex+'.tmp'
  with files.open(temporary,'wb') as f:f.write(ET.tostring(root,encoding='utf-8',xml_declaration=True))
  with files.open(catalog,'rb') as f:
   if f.read()!=original:raise RuntimeError('Catalog changed; refuse overwrite')
  # Compact prior descriptor retained alongside ordinary plugin staging input.
  backup=directory+'/adaptive-java-original.xml'
  try:
   if not stat.S_ISREG(files.lstat(backup).st_mode):raise RuntimeError('Refuse non-regular original descriptor')
  except FileNotFoundError:
   with files.open(backup,'wb') as f:f.write(before)
  files.posix_rename(temporary,catalog)
print(json.dumps({'version':version,'jarSha256':hashlib.sha256(data).hexdigest(),'protectedStockFilesVerified':protected.count(': OK'),
 'nativePackagesAndDependenciesUnchanged':not args.with_output_runtime,'qualifiedEnginesAndDefaultIniUnchanged':True,
 'outputWrapperUpdate':args.with_output_runtime,'installation':'PENDING_NORMAL_PLUGIN_MANAGER'},indent=2))
