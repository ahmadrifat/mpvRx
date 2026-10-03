from pathlib import Path
import zipfile,subprocess,re,io
import argparse,tempfile
args=argparse.ArgumentParser(); args.add_argument('--readelf',required=True); parsed=args.parse_args()
root=Path(__file__).resolve().parents[1]; elf=Path(parsed.readelf).resolve()
workspace=Path(tempfile.mkdtemp(prefix='mpvrx-native-'))
platform={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libmediandk.so','libOpenSLES.so','libc++_shared.so','libGLESv2.so','libEGL.so','libvulkan.so'}
for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
 dest=root/f'app/src/main/jniLibs/{abi}'; archive=dest/'libffmpeg.zip.so'; z=zipfile.ZipFile(archive); names={n.rsplit('/',1)[-1]:n for n in z.namelist() if n.startswith('usr/lib/') and '/' not in n[8:] and '.so' in n}
 tmp=workspace/abi;tmp.mkdir(parents=True,exist_ok=True)
 def data(name):
  result=z.read(names[name])
  for _ in range(10):
   if result[:4]==b'\x7fELF':return result
   name=result.decode().strip();result=z.read(names[name])
  raise ValueError(name)
 def needed(path):
  text=subprocess.check_output([str(elf),'-d',str(path)]).decode();return re.findall(r'Shared library: \[(.*?)\]',text)
 pending=needed(dest/'libffmpeg.so')+needed(dest/'libffprobe.so'); selected={}
 while pending:
  name=pending.pop()
  if name in platform or name in selected:continue
  assert name in names,name
  raw=data(name); selected[name]=raw;p=tmp/name;p.write_bytes(raw);pending.extend(needed(p))
 z.close()
 before=archive.stat().st_size
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as out:
  for name,raw in selected.items():out.writestr('usr/lib/'+name,raw)
 print(abi,before,archive.stat().st_size,len(selected))

import shutil
shutil.rmtree(workspace)
