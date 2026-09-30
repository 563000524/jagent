import json, glob, os

def text_of(m):
    return ''.join(b.get('text', '') for b in (m.get('content') or [])
                   if isinstance(b, dict) and b.get('type') == 'text')

root = os.path.expanduser('~/.jagent/cjh')  # not used, state lives in workspace
base = r'C:\IDEAWorkSpace\015-fast-agent\.jagentspace\.state\cjh'
for p in sorted(glob.glob(os.path.join(base, '*', 'agent_state.json'))):
    d = json.load(open(p, encoding='utf-8'))
    ctx = d.get('context') or []
    out = []
    for m in ctx:
        role = 'user' if str(m.get('role')).upper() == 'USER' else 'assistant'
        t = text_of(m)
        if not t.strip():
            continue
        if out and out[-1][0] == 'assistant' and role == 'assistant':
            out[-1] = (role, out[-1][1] + '\n\n' + t)
        else:
            out.append((role, t))
    print('====', os.path.basename(os.path.dirname(p)), 'messages-view:', len(out))
    for i, (r, t) in enumerate(out):
        print('   ', i, r, len(t), repr(t[:40]))
