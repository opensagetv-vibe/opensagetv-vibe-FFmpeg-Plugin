#!/usr/bin/env python3
"""Read-only installed output/policy byte verification; ignores ZIP timestamps."""
import hashlib,importlib.util,io,json,shlex,zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1];web=root.parent/'opensagetv-vibe-WebClient-Plugin'
if not web.is_dir():web=root.parent/'web-client-plugin'
spec=importlib.util.spec_from_file_location('commission',web/'scripts/commission-mobile.py')
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C);cfg=C.configuration()
if cfg['address']!='192.168.10.232':raise SystemExit('Exact stock232 required')
local=(root/'dist/dev/SageTVFFmpegPlugin.jar').read_bytes()
with C.ssh(cfg) as ssh:
    name=shlex.quote(cfg['unraid_container_name'])
    inspect=json.loads(C.remote(ssh,'docker inspect '+name))[0]
    mounts=[m['Source'] for m in inspect['Mounts'] if m['Destination']=='/opt/sagetv']
    assert len(mounts)==1 and mounts[0].startswith('/mnt/user/appdata/')
    with ssh.open_sftp() as sftp:
        with sftp.open(mounts[0]+'/server/JARs/SageTVFFmpegPlugin.jar','rb') as source:installed=source.read(8*1024*1024)
        with sftp.open(mounts[0]+'/server/plugins/SageTVFFmpegPlugin/runtime/ffmpeg_MIM','rb') as source:wrapper=source.read(8*1024*1024)
    protected=C.remote(ssh,'docker exec -w /opt/sagetv/server '+name+' sha256sum -c /usr/local/share/sagetv-stock/protected.sha256')
def content(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:return {n:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if not n.endswith('/')}
assert content(local)==content(installed),'Installed class/resource content differs from tested source build'
assert hashlib.sha256(wrapper).digest()==hashlib.sha256((Path('/project/output/linux-x64/ffmpeg_MIM')).read_bytes()).digest(),'Installed wrapper differs'
assert protected.count(': OK')==20
print(json.dumps(dict(passed=True,installedJarSha256=hashlib.sha256(installed).hexdigest(),localJarSha256=hashlib.sha256(local).hexdigest(),identicalClassAndResourceEntries=len(content(local)),installedWrapperSha256=hashlib.sha256(wrapper).hexdigest(),protectedStockFiles=20)))
