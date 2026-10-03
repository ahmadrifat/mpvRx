"""Recreate the bundled clipping runtime from pinned Maven artifacts. Python 3."""
from pathlib import Path
from urllib.request import urlopen
import io, zipfile, hashlib
BASE = 'https://repo.maven.apache.org/maven2/io/github/deniscerri/youtubedl-android/'
HASHES = {'ffmpeg': 'b4e321b93b4d70d2eea3b706b36b5b10618dfc0cec6eb0cc261c3d4fad88df54',
          'library': 'c5fdca03d5b82e838fa846783e57eb0a48d3a930070f2ade50402b6c37cad61c'}
def artifact(name):
    data = urlopen(f'{BASE}{name}/0.19.0/{name}-0.19.0.aar').read()
    assert hashlib.sha256(data).hexdigest() == HASHES[name], 'Unexpected artifact contents'
    print(name, hashlib.sha256(data).hexdigest())
    return zipfile.ZipFile(io.BytesIO(data))
ff, py = artifact('ffmpeg'), artifact('library')
root = Path(__file__).resolve().parents[1] / 'app/src/main/jniLibs'
for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
    dest = root / abi
    dest.mkdir(parents=True, exist_ok=True)
    for exe in ['libffmpeg.so', 'libffprobe.so']:
        (dest / exe).write_bytes(ff.read(f'jni/{abi}/{exe}'))
    archive = zipfile.ZipFile(io.BytesIO(ff.read(f'jni/{abi}/libffmpeg.zip.so')))
    python = zipfile.ZipFile(io.BytesIO(py.read(f'jni/{abi}/libpython.zip.so')))
    with zipfile.ZipFile(dest / 'libffmpeg.zip.so', 'w', zipfile.ZIP_DEFLATED) as out:
        for entry in archive.infolist():
            out.writestr(entry, archive.read(entry))
        for entry in python.infolist():
            name = entry.filename
            if name.startswith('usr/lib/') and '/' not in name[8:] and '.so' in name and 'python' not in name and name not in archive.namelist():
                out.writestr(entry, python.read(entry))
