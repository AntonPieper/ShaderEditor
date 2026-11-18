"""Numbers for section 8: the kill criterion per stack, and what the
strict reader would add beside what the app shows today, under the
study's rules (shown only when the driver refused; where the driver's
line is on or next to the reader's, or the driver gives no line).
Usage: python3 -I decision.py <typo-probe dir> [app voice, default app; app-head]"""
import json, os, sys, collections as C
root = sys.argv[1]
rows = [json.loads(l) for l in open(os.path.join(root, 'analysis/scored.jsonl'))]
idx = {r['id']: r for r in map(json.loads, open(os.path.join(root, 'corpus/index.jsonl')))}
by = {(r['id'], r['voice'], r['device']): r for r in rows}
good = lambda r: r is not None and r['lineScore'] in ('exact', '±1') and r['messageScore'] == 2
usable = lambda r: r is not None and r['lineScore'] in ('exact', '±1') and r['messageScore'] >= 1
APP = sys.argv[2] if len(sys.argv) > 2 else 'app'
for d in sorted({r['device'] for r in rows if r['device']}):
    for v in ['driver', APP]:
        rs = [r for r in rows if r['voice'] == v and r['device'] == d]
        n = len(rs)
        print(f"{d:14} {v:6} n={n} line±1={sum(r['lineScore'] in ('exact','±1') for r in rs)/n:.0%} "
              f"msg2={sum(r['messageScore']==2 for r in rs)/n:.0%} both(msg2)={sum(map(good,rs))} {sum(map(good,rs))/n:.1%} "
              f"both(msg>=1)={sum(map(usable,rs))/n:.1%}")
    # the reader beside the app
    apps = [r for r in rows if r['voice'] == APP and r['device'] == d]
    helped = C.Counter(); worse = C.Counter(); bad = C.Counter(); n = len(apps)
    for a in apps:
        rd = by[(a['id'], 'reader', None)]
        shown = rd['status'].startswith('error') and (
            a['line'] in (0, None) or a['file'] is None or
            (rd['file'] == a['file'] and abs(rd['line'] - a['line']) <= 1))
        if not good(a):
            bad[a['kind']] += 1
            if shown and good(rd):
                helped[a['kind']] += 1
        if shown and not good(rd) and good(a):
            worse[a['kind']] += 1
    print(f"   app not right: {sum(bad.values())}/{n}; reader shown and right there: {sum(helped.values())} "
          f"({sum(helped.values())/n:.1%} of all typos) by kind {dict(sorted(helped.items()))}; "
          f"not-right by kind {dict(sorted(bad.items()))}; reader shown, wrong, beside a right app row: {sum(worse.values())} {dict(sorted(worse.items()))}")
