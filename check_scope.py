#!/usr/bin/env python3
"""Catch Hetu runtime identifier-resolution failures that compiling misses.

Any std type used at runtime must be declared in the file that uses it --
including the class behind an instance (utf8 -> Utf8Codec) and the type of
every .then callback parameter (HttpResponse).
"""
import glob, re, sys

# trigger in source -> identifier that must be declared
RULES = [
    (r'\.then\(',        'HttpResponse', 'a .then callback parameter is typed HttpResponse'),
    (r'\butf8\.',        'Utf8Codec',    'utf8 is an instance of Utf8Codec'),
    (r'\bBase64\.',      'Base64',       'Base64 static call'),
    (r'\bJSON\.',        'JSON',         'JSON static call'),
    (r'\bRequestOptions\(', 'RequestOptions', 'RequestOptions constructor'),
    (r'\bHttpBaseOptions\(', 'HttpBaseOptions', 'HttpBaseOptions constructor'),
    (r'\bHttpClient\(',  'HttpClient',   'HttpClient constructor'),
]

failed = False
for path in sorted(glob.glob('src/**/*.ht', recursive=True)):
    src = open(path).read()
    body = re.sub(r'//.*', '', src)
    declared = set(re.findall(r'var\s+(\w+)\s*=\s*std\.', src))
    problems = []
    for pattern, ident, why in RULES:
        if re.search(pattern, body) and ident not in declared:
            problems.append(f'{ident} ({why})')
    status = 'BAD ' if problems else 'OK  '
    if problems:
        failed = True
    print(f'{status}{path}')
    print(f'      declared: {sorted(declared) or "none"}')
    for p in problems:
        print(f'      MISSING: {p}')

print('\nRESULT:', 'FAIL' if failed else 'every std identifier used is declared in scope')
sys.exit(1 if failed else 0)
