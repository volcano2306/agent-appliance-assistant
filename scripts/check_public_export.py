"""Read only public sources; reject secrets by filename before opening them."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

BLOCKED_DIRS = {'.git', '.idea', '.vscode', '.venv', '.venv311', 'venv', 'node_modules',
                'target', '__pycache__', '.pytest_cache', '.npm-cache', 'reports', 'eval-results', 'uploads'}
BLOCKED_SUFFIXES = {'.log', '.pyc', '.pyo', '.jar', '.class', '.pem', '.key', '.p12', '.pfx', '.docx', '.xlsx', '.pdf', '.bak', '.tmp'}
BLOCKED_NAMES = {'project.private.config.json', 'application-local.properties', 'application-local.yml', 'compose.local.yml'}
TEXT = {'.py', '.java', '.js', '.json', '.md', '.ps1', '.properties', '.wxml', '.wxss', '.ts', '.txt', '.xml', '.yml'}
PRIVATE_PATH = re.compile(r'(?i)(?<![A-Za-z0-9_])[A-Z]:[\\/]|(?<![A-Za-z0-9_/])/(?:Users|home)/[A-Za-z0-9_.-]+/')
KEY_SHAPE = re.compile(r'\b(?:sk-[A-Za-z0-9_-]{16,}|AKID[A-Za-z0-9]{16,}|LTAI[A-Za-z0-9]{16,}|AKIA[A-Z0-9]{16})\b')
PERSONAL_APPID = re.compile(r'\bwx[a-f0-9]{16}\b')
PHONE = re.compile(r'(?<!\d)1[3-9]\d{9}(?!\d)')
EMAIL = re.compile(r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}')


def inspect_png(path):
    data = path.read_bytes()
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        return ['invalid_png']
    result = []
    pos = 8
    while pos + 12 <= len(data):
        size = struct.unpack('>I', data[pos:pos + 4])[0]
        chunk = data[pos + 4:pos + 8]
        if chunk in {b'tEXt', b'zTXt', b'iTXt', b'eXIf'}:
            result.append('png_text_or_exif_metadata')
        pos += 12 + size
        if chunk == b'IEND':
            break
    return result


def check(root):
    failures, counts, all_files = [], {'files': 0, 'bytes': 0}, []
    def issue(path, category):
        failures.append({'file': path.relative_to(root).as_posix(), 'category': category})
    for base, dirs, files in os.walk(root):
        allowed = []
        for name in dirs:
            path = Path(base, name)
            if path.is_symlink():
                issue(path, 'symlink')
            elif name in BLOCKED_DIRS:
                issue(path, 'excluded_directory')
            else:
                allowed.append(name)
        dirs[:] = allowed
        for name in files:
            path = Path(base, name)
            # Never read a dotenv secret, even if one was accidentally added.
            if name.startswith('.env') and name != '.env.example':
                issue(path, 'dotenv_secret_filename')
                continue
            if name in BLOCKED_NAMES or path.suffix.lower() in BLOCKED_SUFFIXES:
                issue(path, 'excluded_file')
                continue
            if path.is_symlink():
                issue(path, 'symlink')
                continue
            all_files.append(path)
            counts['files'] += 1
            counts['bytes'] += path.stat().st_size
            if path.suffix.lower() == '.png':
                for category in inspect_png(path):
                    issue(path, category)
            if path.suffix.lower() not in TEXT and name not in {'.gitignore', '.gitattributes', '.dockerignore', '.env.example', 'Dockerfile', 'LICENSE'}:
                continue
            content = path.read_text(encoding='utf-8-sig')
            relative = path.relative_to(root).as_posix()
            # Tokenizer vocabulary naturally contains words/numbers that match patterns.
            if relative != 'agent/assets/deepseek-v4/tokenizer.json':
                for pattern, category in ((PRIVATE_PATH, 'absolute_personal_path'), (KEY_SHAPE, 'credential_shape'), (PERSONAL_APPID, 'personal_appid')):
                    if pattern.search(content):
                        issue(path, category)
                if not relative.startswith(('agent/tests/', 'BE/src/test/', 'FE/tests/')):
                    if PHONE.search(content):
                        issue(path, 'phone_outside_synthetic_tests')
                    for match in EMAIL.finditer(content):
                        if not match.group().lower().endswith(('@example.com', '@example.org', '@example.net')):
                            issue(path, 'email_outside_synthetic_tests')
            try:
                if path.suffix == '.py':
                    ast.parse(content)
                elif path.suffix == '.json':
                    json.loads(content)
                elif path.suffix == '.xml':
                    ET.fromstring(content)
            except Exception:
                issue(path, 'syntax_or_structure')
            if path.suffix == '.md':
                for target in re.findall(r'\[[^\]]*\]\(([^)\s]+)\)', content):
                    if '://' not in target and not target.startswith('#'):
                        if not (path.parent / target.split('#', 1)[0]).exists():
                            issue(path, 'missing_document_link')

    config = json.loads((root / 'FE/app.json').read_text(encoding='utf-8'))
    for page in config['pages']:
        for suffix in ('.js', '.json', '.wxml', '.wxss'):
            if not (root / 'FE' / (page + suffix)).is_file():
                issue(root / 'FE/app.json', 'missing_page_resource')
    for path in (root / 'FE').rglob('*.wxml'):
        for image in re.findall(r'src=[\"\'](/images/[^\"\'{}]+)[\"\']', path.read_text(encoding='utf-8')):
            if not (root / 'FE' / image.lstrip('/')).is_file():
                issue(path, 'missing_image')
    expected = '8f9f37ca37fdc4f5fd36d5cf4d3b0e8392edb4e894fd10cc0d70b4957c8633cf'
    tokenizer = root / 'agent/assets/deepseek-v4/tokenizer.json'
    if hashlib.sha256(tokenizer.read_bytes()).hexdigest() != expected:
        issue(tokenizer, 'tokenizer_checksum')
    return {'passed': not failures, **counts, 'issues': failures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    result = check(args.root.resolve())
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
