"""Prüft die eigenen Core-Shader ohne Spielstart: übersetzt `shaders/core/*.vsh/.fsh` nach GLSL ES 3.00 (Includes aus
`shaders/include` des Addons und der Spiel-Jar), kompiliert und linkt sie in WebGL2 (Headless-Chrome mit SwiftShader).
Ein global registrierter Shader mit Fehler würde im Spiel den ganzen Ressourcen-Reload scheitern lassen.

    python tools/assets/glcheck.py            # alle Paare vsh/fsh unter assets/hha_arius/shaders/core

GLSL ES 3.00 ist strenger als das GLSL 330 des Spiels; was hier durchgeht, kompiliert erfahrungsgemäß auch im Spiel.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
SHADERS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha_arius', 'shaders')
CLIENT_JAR = os.path.expanduser('~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar')
CHROMES = [r'C:\Program Files\Google\Chrome\Application\chrome.exe', r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe']


def include(ns, name, jar):
    if ns == 'hha_arius':
        return open(os.path.join(SHADERS, 'include', name), encoding='utf8').read()
    return jar.read('assets/minecraft/shaders/include/' + name).decode('utf8')


def to_es(src, jar, depth=0):
    out = []
    for line in src.splitlines():
        if line.startswith('#version'):
            if depth == 0:
                out += ['#version 300 es', 'precision highp float;', 'precision highp int;']
            continue
        m = re.match(r'\s*#moj_import\s*<([a-z_]+):([^>]+)>', line)
        if m:
            out.append(to_es(include(m.group(1), m.group(2), jar), jar, depth + 1))
            continue
        out.append(line)
    return '\n'.join(out)


JS = '''
const out = document.getElementById('out');
try {
  const gl = document.createElement('canvas').getContext('webgl2');
  const res = {};
  for (const [name, vs, fs] of __P__) {
    const mk = (t, s) => { const o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o);
      return [o, gl.getShaderParameter(o, gl.COMPILE_STATUS), gl.getShaderInfoLog(o)]; };
    const v = mk(gl.VERTEX_SHADER, vs), f = mk(gl.FRAGMENT_SHADER, fs);
    let link = 'skip';
    if (v[1] && f[1]) { const p = gl.createProgram(); gl.attachShader(p, v[0]); gl.attachShader(p, f[0]); gl.linkProgram(p);
      link = gl.getProgramParameter(p, gl.LINK_STATUS) ? 'ok' : gl.getProgramInfoLog(p); }
    res[name] = {vs: v[1] ? 'ok' : v[2], fs: f[1] ? 'ok' : f[2], link: link};
  }
  out.textContent = 'RESULT' + JSON.stringify(res);
} catch (e) { out.textContent = 'RESULT' + JSON.stringify({error: String(e)}); }
'''


def main():
    jar = zipfile.ZipFile(CLIENT_JAR)
    core = os.path.join(SHADERS, 'core')
    pairs = []
    for f in sorted(os.listdir(core)):
        if f.endswith('.fsh'):
            name = f[:-4]
            vs = to_es(open(os.path.join(core, name + '.vsh'), encoding='utf8').read(), jar)
            fs = to_es(open(os.path.join(core, f), encoding='utf8').read(), jar)
            fs = fs.replace('precision highp int;', 'precision highp int;\nprecision highp sampler2D;', 1)
            pairs.append([name, vs, fs])
    exe = next((c for c in CHROMES if os.path.exists(c)), None) or shutil.which('chrome')
    tmp = tempfile.mkdtemp()
    page = os.path.join(tmp, 'check.html')
    with open(page, 'w', encoding='utf8') as fh:
        fh.write('<!doctype html><html><body><pre id="out">pending</pre><script>' + JS.replace('__P__', json.dumps(pairs)) + '</script></body></html>')
    run = subprocess.run([exe, '--headless=new', '--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--enable-webgl',
                          '--ignore-gpu-blocklist', '--virtual-time-budget=8000', '--dump-dom', 'file:///' + page.replace('\\', '/')],
                         capture_output=True, text=True, encoding='utf8', errors='replace')
    m = re.search(r'<pre id="out">RESULT(.*?)</pre>', run.stdout, re.S)
    if not m:
        print(run.stdout[-2000:], run.stderr[-2000:])
        sys.exit(2)
    res = json.loads(m.group(1).replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>'))
    bad = False
    for name, r in res.items():
        print(name, r)
        if not isinstance(r, dict) or any(v != 'ok' for v in r.values()):
            bad = True
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
