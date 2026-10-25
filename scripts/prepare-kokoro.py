#!/usr/bin/env python3
"""Fetch pinned, verified Kokoro assets and the Android runtime before building."""
from pathlib import Path
import hashlib
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MODEL_SHA = 'e0530c5031c03c5dc957edfc6d72582c2246e9b4566643ff67eeb03610c11641'
AAR_SHA = '6994c53380846107b0363fee02fa9db6176c61c746a2315893c6b01e7f6d4c1d'
ARCHIVE_SHA = 'c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd'
BASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'

def checksum(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def download(url, output, expected):
    print('Downloading', output.name, flush=True)
    with urllib.request.urlopen(url, timeout=120) as response, output.open('wb') as out:
        shutil.copyfileobj(response, out)
    if checksum(output) != expected:
        raise RuntimeError('SHA-256 mismatch: ' + output.name)

def main():
    aar = ROOT / 'app/libs/sherpa-onnx-1.12.15.aar'
    model = ROOT / 'app/src/main/assets/kokoro'
    with tempfile.TemporaryDirectory(prefix='wordflow-kokoro-') as folder:
        temp = Path(folder)
        if not aar.is_file() or checksum(aar) != AAR_SHA:
            target = temp / aar.name
            download(BASE + 'v1.12.15/' + aar.name, target, AAR_SHA)
            aar.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(target, aar)
        if not (model / 'model.int8.onnx').is_file() or checksum(model / 'model.int8.onnx') != MODEL_SHA or not (model / 'voices.bin').is_file() or not (model / 'espeak-ng-data/en_dict').is_file():
            archive = temp / 'kokoro-int8-en-v0_19.tar.bz2'
            download(BASE + 'tts-models/' + archive.name, archive, ARCHIVE_SHA)
            with tarfile.open(archive, 'r:bz2') as tar:
                for member in tar.getmembers():
                    destination = (temp / member.name).resolve()
                    if temp.resolve() not in destination.parents or not (member.isfile() or member.isdir()):
                        raise RuntimeError('Unsafe model archive entry: ' + member.name)
                tar.extractall(temp)
            extracted = temp / 'kokoro-int8-en-v0_19'
            if checksum(extracted / 'model.int8.onnx') != MODEL_SHA:
                raise RuntimeError('Extracted model checksum mismatch')
            shutil.copytree(extracted, model, dirs_exist_ok=True)
    print('Kokoro model and sherpa-onnx runtime verified.')

if __name__ == '__main__':
    main()
