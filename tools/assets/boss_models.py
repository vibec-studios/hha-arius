"""Alle Boss-Modelle: Texturen und Kotlin-Modelldaten (client/model/<Name>ModelData.kt).

Aufruf:
    python boss_models.py                 # alle Modelle neu erzeugen
    python boss_models.py --only hades    # nur dieses Modell (Komma-Liste erlaubt), die anderen bleiben, wie sie sind
    python boss_models.py --list          # bekannte Modellnamen

Jedes Modell lebt in seiner eigenen Datei (siehe REGISTRY). Ein Modul darf zusätzlich `variants(model, albedo, glow)`
anbieten, um aus dem fertigen Atlas weitere Texturen abzuleiten (z. B. den Schatten des Hermes).

Der Kotlin-Teil jedes Modells wird als Teilstück unter tools/assets/gen/<name>.kt.part abgelegt; <Name>ModelData.kt
wird danach immer aus allen Teilstücken zusammengesetzt. So können mehrere Leute gleichzeitig an verschiedenen
Modellen arbeiten, ohne sich gegenseitig die Daten zu überschreiben oder mit einem halbfertigen Modell zu stören.
"""
import importlib
import os
import sys

from modelkit import assemble_kotlin, model_chunk
from texkit import flush

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, 'gen')

# Modellname -> (Modul, Bau-Funktion). Die Reihenfolge bestimmt nur die Reihenfolge in <Name>ModelData.kt.
REGISTRY = {
    'arius': ('arius_model', 'arius'),
    'nivaris': ('nivaris_model', 'nivaris'),
    'gauntlets': ('gauntlets_model', 'gauntlets'),
    'armor': ('armor_model', 'armor'),
}


def build(name):
    """Baut ein Modell aus der Registry (für Vorschau-Skripte)."""
    module, fn = REGISTRY[name]
    model = getattr(importlib.import_module(module), fn)()
    if model.name != name:
        raise SystemExit(f'{module}.{fn}() liefert Modell "{model.name}", erwartet "{name}"')
    return model


def generate(name):
    module, _ = REGISTRY[name]
    model = build(name)
    model.pack()
    albedo, glow = model.paint()
    variants = getattr(importlib.import_module(module), 'variants', None)
    if variants is not None:
        variants(model, albedo, glow)
    os.makedirs(GEN, exist_ok=True)
    part = os.path.join(GEN, f'{name}.kt.part')
    tmp = f'{part}.{os.getpid()}.tmp'
    with open(tmp, 'w', encoding='utf8', newline='\n') as f:
        f.write(model_chunk(model))
    os.replace(tmp, part)


def assemble(names=None):
    for name in (names or REGISTRY):
        part = os.path.join(GEN, f'{name}.kt.part')
        if not os.path.exists(part):
            print('Teilstück fehlt:', name)
            continue
        with open(part, encoding='utf8') as f:
            assemble_kotlin([f.read()], name)


def main(argv):
    if '--list' in argv:
        print('\n'.join(REGISTRY))
        return
    names = list(REGISTRY)
    if '--only' in argv:
        names = [n.strip() for n in argv[argv.index('--only') + 1].split(',') if n.strip()]
        unknown = [n for n in names if n not in REGISTRY]
        if unknown:
            raise SystemExit('Unbekannte Modelle: ' + ', '.join(unknown) + ' (siehe --list)')
    for name in names:
        generate(name)
    flush()
    assemble(names)


if __name__ == '__main__':
    main(sys.argv[1:])
