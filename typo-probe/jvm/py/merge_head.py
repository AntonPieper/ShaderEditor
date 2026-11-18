"""Adds the app's voices from a run with the app classes of another
head (analysis/voices-app-1089ba0e-raw.jsonl, made by
APP=<checkout at 1089ba0e> OUT=... jvm/analyse.sh) to analysis/voices.jsonl
as 'app-head' and 'app-head-on-mesa-log'. Run after analyse.sh.
Usage: python3 -I merge_head.py <typo-probe dir>"""
import json, os, sys
root = sys.argv[1]
p = os.path.join(root, 'analysis/voices.jsonl')
rows = [l for l in open(p) if '"voice":"app-head' not in l]
for l in open(os.path.join(root, 'analysis/voices-app-1089ba0e-raw.jsonl')):
    r = json.loads(l)
    if r['voice'] in ('app', 'app-on-mesa-log'):
        r['voice'] = r['voice'].replace('app', 'app-head', 1)
        rows.append(json.dumps(r) + '\n')
open(p, 'w').write(''.join(rows))
