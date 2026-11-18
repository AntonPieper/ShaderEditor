"""Message keys of the typo experiment: what the blind scorer sees.

A key is (kind, normalised first message). The word the typo is about
(the misspelled word, the called function, the declared or moved name)
becomes <<WORD>>, any other name in quotes becomes X, a number N.
Usage: python3 -I keys.py <typo-probe dir>   (writes analysis/keys.jsonl)
"""
import json, re, sys, os

root = sys.argv[1]
index = {}
for l in open(os.path.join(root, 'corpus/index.jsonl')):
    r = json.loads(l)
    index[r['id']] = r

def offset(text, line, col):
    lines = text.split('\n')
    return sum(len(x) + 1 for x in lines[:line - 1]) + col - 1

def subject(m):
    k = m['kind']
    if k in ('K04', 'K05', 'K09'):
        return m['after']
    if k == 'K12':
        if m['sub'].startswith('local'):
            return re.match(r'line \d+: (?:const\s+)?\w+\s+(\w+)', m['before']).group(1)
        return re.search(r'(\w+)\s*\(', m['before'].split(': ', 1)[1]).group(1)
    src = open(os.path.join(root, 'corpus/texts', m['id'] + '.src')).read()
    at = offset(src, m['line'], m['col'])
    if k == 'K06':
        d = 0
        i = at - 1
        while i >= 0:
            c = src[i]
            if c == ')':
                d += 1
            elif c == '(':
                if d == 0:
                    break
                d -= 1
            i -= 1
        return re.search(r'(\w+)\s*$', src[:i]).group(1)
    if k == 'K07':
        return re.match(r'vec\d\s+(\w+)', src[at:]).group(1)
    return None

def normalise(msg, word):
    if msg is None:
        return ''
    s = msg
    if word:
        s = re.sub(r'\b' + re.escape(word) + r'\b', '<<WORD>>', s)
    def quoted(q):
        inner = q.group(2)
        if '<<WORD>>' in inner:
            return q.group(1) + inner + q.group(3)
        if re.fullmatch(r'[A-Za-z_]\w*', inner):
            return q.group(1) + 'X' + q.group(3)
        return q.group(0)
    s = re.sub(r"(['`])([^'`]*)(')", quoted, s)
    s = re.sub(r'(?<![\w.])\d+(\.\d+)?(?![\w.])', 'N', s)
    return s.strip()

if __name__ == '__main__':
    rows = [json.loads(l) for l in open(os.path.join(root, 'analysis/voices.jsonl'))]
    out = open(os.path.join(root, 'analysis/keys.jsonl'), 'w')
    for r in rows:
        if r['kind'] == 'none':
            continue
        m = index[r['id']]
        status = r['status']
        if status.startswith('accepted') or status.startswith('n/a') or \
                status.startswith('app stops: needs'):
            continue
        w = subject(m)
        msg = r.get('message', '')
        key = None if not msg else m['kind'] + ' | ' + normalise(msg, w)
        out.write(json.dumps({'id': r['id'], 'voice': r['voice'],
                              'device': r['device'], 'key': key}) + '\n')
