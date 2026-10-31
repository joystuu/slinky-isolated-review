"""Parse class-file metadata only; never import or execute payload code.

Usage: python tools/static-reference-check.py --baseline-dir PATH --output PATH
Run from the project root after build. The baseline directory must contain
payload-101.jar, payload-102.jar, payload-103.jar when comparing original samples.
"""
import argparse, collections, hashlib, json, pathlib, struct, zipfile

# The game references these inherited Java 8 API methods using subclass owners.
# This small explicit API metadata table avoids loading either game or JVM code.
JDK_API = {
    'java/lang/Enum': dict(parents=['java/lang/Object'], fields=set(), methods={('ordinal','()I')}),
    'java/lang/Exception': dict(parents=['java/lang/Throwable'], fields=set(), methods=set()),
    'java/lang/Throwable': dict(parents=['java/lang/Object'], fields=set(), methods={('getMessage','()Ljava/lang/String;')}),
}

def parse_class(blob):
    assert blob[:4] == b'\xca\xfe\xba\xbe'
    p = 8
    def u1():
        nonlocal p
        n = blob[p]; p += 1; return n
    def u2():
        nonlocal p
        n = struct.unpack_from('>H', blob, p)[0]; p += 2; return n
    def u4():
        nonlocal p
        n = struct.unpack_from('>I', blob, p)[0]; p += 4; return n
    count = u2(); cp = [None] * count; i = 1
    while i < count:
        tag = u1()
        if tag == 1:
            length = u2(); cp[i] = (tag, blob[p:p+length].decode('utf-8', 'replace')); p += length
        elif tag in (3, 4): cp[i] = (tag, u4())
        elif tag in (5, 6): cp[i] = (tag, u4(), u4()); i += 1
        elif tag in (7, 8, 16, 19, 20): cp[i] = (tag, u2())
        elif tag in (9, 10, 11, 12, 17, 18): cp[i] = (tag, u2(), u2())
        elif tag == 15: cp[i] = (tag, u1(), u2())
        else: raise ValueError(f'Unsupported constant tag {tag}')
        i += 1
    def text(i): return cp[i][1]
    def cls(i): return text(cp[i][1]) if i else None
    access = u2(); name = cls(u2()); parent = cls(u2())
    parents = ([parent] if parent else []) + [cls(u2()) for _ in range(u2())]
    def skip_attrs():
        nonlocal p
        for _ in range(u2()):
            u2(); length = u4(); p += length
    fields, methods = set(), set()
    for dest in (fields, methods):
        for _ in range(u2()):
            u2(); member_name = text(u2()); descriptor = text(u2())
            dest.add((member_name, descriptor)); skip_attrs()
    refs = set()
    for item in cp:
        if item and item[0] in (9,10,11):
            owner = cls(item[1]); nt = cp[item[2]]
            refs.add(('field' if item[0] == 9 else 'method', owner, text(nt[1]), text(nt[2])))
    return dict(name=name, parents=parents, fields=fields, methods=methods, refs=refs)

def read_jar(path):
    with zipfile.ZipFile(path) as jar:
        return {v['name']:v for n in jar.namelist() if n.endswith('.class') for v in [parse_class(jar.read(n))]}

def resolve(ref, game):
    kind, owner, name, desc = ref
    seen = set(); todo = collections.deque([owner])
    while todo:
        owner = todo.popleft()
        if owner in seen: continue
        seen.add(owner); info = game.get(owner) or JDK_API.get(owner)
        if info is None: continue
        if (name,desc) in info[kind+'s']: return (kind,owner,name,desc)
        if name != '<init>': todo.extend(info['parents'])
    return None

def audit(path, game):
    classes=read_jar(path); refs=set()
    for cls in classes.values(): refs.update(r for r in cls['refs'] if r[1] in game)
    resolved=set(); unresolved=[]
    for ref in sorted(refs):
        canonical=resolve(ref,game)
        if canonical: resolved.add(canonical)
        else: unresolved.append(ref)
    return dict(class_count=len(classes), unique_game_references=len(refs),
                resolved_game_references=len(resolved), unresolved_game_references=unresolved),resolved

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--baseline-dir',required=True)
    parser.add_argument('--output',required=True); args=parser.parse_args()
    root=pathlib.Path(__file__).resolve().parent.parent; baseline=pathlib.Path(args.baseline_dir)
    result={'method':'Static class-file constant-pool and inheritance metadata parsing; no payload classes loaded or executed.',
            'jdk_api_scope':'Explicit Java 8 metadata for Enum.ordinal and Throwable.getMessage, including Exception inheritance.', 'variants':{}}
    for variant,game_name in [(101,'minecraft-1.8.9-named.jar'),(102,'minecraft-1.8.9-srg.jar'),(103,'minecraft-1.8.9-official.jar')]:
        game=read_jar(root/'deps'/game_name)
        original,original_refs=audit(baseline/f'payload-{variant}.jar',game)
        rebuilt,rebuilt_refs=audit(root/'build'/f'payload-{variant}.jar',game)
        result['variants'][str(variant)]={'original':original,'rebuilt':rebuilt,
          'added_resolved_references':sorted(rebuilt_refs-original_refs),
          'removed_resolved_references':sorted(original_refs-rebuilt_refs),
          'new_unresolved_references':sorted(set(map(tuple,rebuilt['unresolved_game_references']))-set(map(tuple,original['unresolved_game_references'])))}
    output=pathlib.Path(args.output); output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,indent=2)+'\n')
    for variant,info in result['variants'].items():
        print(variant, 'game refs',info['rebuilt']['unique_game_references'],'unresolved',len(info['rebuilt']['unresolved_game_references']),
              'new unresolved',len(info['new_unresolved_references']),'added',len(info['added_resolved_references']),'removed',len(info['removed_resolved_references']))
if __name__=='__main__': main()
