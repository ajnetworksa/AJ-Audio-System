"""Checks that every music_assistant import of the ajsnap provider exists in the Music Assistant version we build on."""
import ast, sys, os, importlib.util, re
FIX=lambda src: re.sub(r'except ([A-Za-z_.]+(?:, *[A-Za-z_.]+)+)( as \w+)?:', lambda m: 'except (%s)%s:' % (m.group(1), m.group(2) or ''), src)
# usage: check_ajsnap_imports.py <dir with music_assistant/ (target MA version, ajsnap copied into providers/) and music_assistant_models/>
root = sys.argv[1]
bad = 0
def mod_path(m):
    p = os.path.join(root, *m.split('.'))
    if os.path.isdir(p): return os.path.join(p, '__init__.py')
    if os.path.isfile(p + '.py'): return p + '.py'
    return None
def defines(path, name):
    src = open(path).read()
    try: t = ast.parse(FIX(src))
    except SyntaxError:
        import re
        return bool(re.search(r'(^|\n)\s*(async def|def|class) %s\b|(^|\n)%s\s*[:=]|import[^\n]*\b%s\b|^\s+%s,' % ((name,)*4), src, re.M))
    for n in ast.walk(t):
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and n.name == name: return True
        if isinstance(n, ast.Assign) and any(getattr(x, 'id', None) == name for x in n.targets): return True
        if isinstance(n, ast.AnnAssign) and getattr(n.target, 'id', None) == name: return True
        if hasattr(ast, 'TypeAlias') and isinstance(n, ast.TypeAlias) and getattr(n.name, 'id', None) == name: return True   # `type X = ...`
        if isinstance(n, (ast.ImportFrom, ast.Import)) and any((a.asname or a.name).split('.')[0] == name for a in n.names): return True
    d = os.path.dirname(path)
    return os.path.exists(os.path.join(d, name + '.py')) or os.path.isdir(os.path.join(d, name))
prov = sys.argv[2] if len(sys.argv) > 2 else 'ajsnap'   # which of our providers to check (ajsnap, ajcast)
pdir = os.path.join(root, 'music_assistant/providers/' + prov)
for f in os.listdir(pdir):
    if not f.endswith('.py'): continue
    t = ast.parse(FIX(open(os.path.join(pdir, f)).read()))
    for n in ast.walk(t):
        if isinstance(n, ast.ImportFrom) and n.module and n.module.split('.')[0] in ('music_assistant', 'music_assistant_models'):
            mp = mod_path(n.module)
            if not mp: print(f, 'MISSING MODULE', n.module); bad += 1; continue
            for a in n.names:
                if not defines(mp, a.name): print(f, 'MISSING', n.module, a.name); bad += 1
print('problems:', bad)
sys.exit(1 if bad else 0)
