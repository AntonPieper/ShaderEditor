"""Tables of the typo experiment from analysis/voices.jsonl, keys.jsonl
and scores.tsv. Writes analysis/scored.jsonl and analysis/tables.md.
Usage: python3 -I tabulate.py <typo-probe dir>
"""
import json, os, sys, collections as C

root = sys.argv[1]
A = lambda *p: os.path.join(root, 'analysis', *p)
voices = [json.loads(l) for l in open(A('voices.jsonl'))]
keys = {(k['id'], k['voice'], k['device']): k['key'] for k in map(json.loads, open(A('keys.jsonl')))}
score = {}
for l in list(open(A('scores.tsv')))[1:]:
    n, s, k = l.rstrip('\n').split('\t', 2)
    score[k] = int(s)

def vname(r):
    return r['voice'] + ('@' + r['device'] if r['device'] else '')

scored = []
for r in voices:
    if r['kind'] == 'none':
        continue
    if r['lineScore'] == '-':
        continue  # accepted / not compiled on this device: nothing to show
    k = keys.get((r['id'], r['voice'], r['device']))
    r['messageKey'] = k
    r['messageScore'] = score[k] if k else 0
    scored.append(r)
with open(A('scored.jsonl'), 'w') as f:
    for r in scored:
        f.write(json.dumps({x: r.get(x) for x in ['id', 'voice', 'device', 'kind', 'status', 'file', 'line', 'lineScore', 'message', 'messageKey', 'messageScore', 'shown']}) + '\n')

KINDS = ['K01', 'K02', 'K03', 'K04', 'K05', 'K06', 'K07', 'K08', 'K09', 'K10', 'K11', 'K12']
NAMES = {'K01': 'missing ;', 'K02': 'parenthesis', 'K03': 'brace', 'K04': 'misspelled name',
         'K05': 'misspelled built-in', 'K06': 'argument count', 'K07': 'vec3 for vec2',
         'K08': 'missing .0', 'K09': 'keyword typo', 'K10': 'open comment', 'K11': '#endif missing',
         'K12': 'use before declaration'}
order = sorted({vname(r) for r in scored}, key=lambda v: (v.split('@')[0] != 'driver', v))
out = []
def pct(a, b):
    return f'{100 * a / b:.0f} %' if b else '-'
for v in order:
    rows = [r for r in scored if vname(r) == v]
    out.append(f'\n### {v} ({len(rows)} texts scored)\n')
    out.append('| Kind | n | line exact | ±1 | elsewhere | none | msg 2 | msg 1 | msg 0 | right line (±1) and msg 2 |')
    out.append('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    tot = C.Counter()
    for k in KINDS:
        rs = [r for r in rows if r['kind'] == k]
        if not rs:
            continue
        c = C.Counter(r['lineScore'] for r in rs)
        m = C.Counter(r['messageScore'] for r in rs)
        both = sum(1 for r in rs if r['lineScore'] in ('exact', '±1') and r['messageScore'] == 2)
        tot.update(c); tot.update({('m', x): y for x, y in m.items()}); tot['n'] += len(rs); tot['both'] += both
        out.append(f"| {k} {NAMES[k]} | {len(rs)} | {c['exact']} | {c['±1']} | {c['elsewhere']} | {c['none']} | {m[2]} | {m[1]} | {m[0]} | {both} |")
    n = tot['n']
    out.append(f"| **all** | {n} | {tot['exact']} ({pct(tot['exact'], n)}) | {tot['±1']} | {tot['elsewhere']} | {tot['none']} | {tot[('m', 2)]} ({pct(tot[('m', 2)], n)}) | {tot[('m', 1)]} | {tot[('m', 0)]} | **{tot['both']} ({pct(tot['both'], n)})** |")
open(A('tables.md'), 'w').write('\n'.join(out) + '\n')
print('\n'.join(out))
