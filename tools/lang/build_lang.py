"""Führt alle Sprachteile tools/lang/<bereich>.<sprache>.json zu assets/hha_arius/lang/<sprache>.json zusammen.

Jede Teilaufgabe schreibt ihre eigenen Teile; dieses Skript vereint sie (alphabetisch nach Bereich, spätere
überschreiben frühere) und meldet Schlüssel, die nur in einer Sprache existieren.
"""
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'hha_arius', 'lang')


def merge(lang):
    data = {}
    for path in sorted(glob.glob(os.path.join(HERE, f'*.{lang}.json'))):
        with open(path, encoding='utf8') as f:
            data.update(json.load(f))
    return data


def main():
    os.makedirs(OUT, exist_ok=True)
    de, en = merge('de_de'), merge('en_us')
    only_de = sorted(set(de) - set(en))
    only_en = sorted(set(en) - set(de))
    for name, data in (('de_de.json', de), ('en_us.json', en)):
        with open(os.path.join(OUT, name), 'w', encoding='utf8', newline='\n') as f:
            json.dump(dict(sorted(data.items())), f, ensure_ascii=False, indent=2)
    print(f'de_de: {len(de)}, en_us: {len(en)}')
    if only_de:
        print('Nur Deutsch:', ', '.join(only_de))
    if only_en:
        print('Nur Englisch:', ', '.join(only_en))


if __name__ == '__main__':
    main()
