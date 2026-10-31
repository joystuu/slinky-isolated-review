"""Static comparison of a fresh build to JARs inside the original EXE.
Usage: python tools/verify-java.py PATH_TO_ORIGINAL_EXE
Executes only trusted compiler/analysis tools, never inspected classes/native code.
"""
import argparse, hashlib, json, pathlib, shutil, subprocess, sys, zipfile
from pe_resources import pe_resources, find_resource

ROOT=pathlib.Path(__file__).resolve().parents[1]
EXPECTED='501d7c83e2010937350fee800e37631e1499ec9b2693a32493db71e5f40b0272'
def sha(b):return hashlib.sha256(b).hexdigest()
def run(*args):subprocess.run(list(map(str,args)),cwd=ROOT,check=True)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('original_exe',type=pathlib.Path);args=p.parse_args()
 sample=args.original_exe.read_bytes();assert sha(sample)==EXPECTED,'Unexpected original specimen hash'
 dll=find_resource(pe_resources(sample),10,200);res=pe_resources(dll)
 workspace=ROOT/'build/java-audit';workspace.mkdir(parents=True,exist_ok=True)
 report={'original_exe_sha256':sha(sample),'original_dll_sha256':sha(dll),'execution':'Static only: inspected native binaries and Java classes are not loaded or executed.','variants':{}}
 java=shutil.which('java');javac=shutil.which('javac');assert java and javac,'JDK required'
 # Compile and run the independent inspector, linked to pinned trusted ASM.
 run(javac,'--release','8','-cp',ROOT/'deps/tooling/*','-d',workspace,ROOT/'tools/BytecodeDump.java')
 cp=str(workspace)+';'+str(ROOT/'deps/tooling/*')
 for n in (101,102,103):
  original=find_resource(res,10,n);origjar=workspace/f'original-{n}.jar';origjar.write_bytes(original)
  rebuilt=ROOT/f'build/payload-{n}.jar';original_json=workspace/f'original-{n}.json';rebuilt_json=workspace/f'rebuilt-{n}.json'
  run(java,'-cp',cp,'BytecodeDump',origjar,original_json)
  run(java,'-cp',cp,'BytecodeDump',rebuilt,rebuilt_json)
  out=ROOT/f'evidence/java-flow-{n}.json'
  run(sys.executable,ROOT/'tools/compare-bytecode.py',original_json,rebuilt_json,out)
  a=json.loads(original_json.read_text());b=json.loads(rebuilt_json.read_text());assert set(b)==set(a)|{'dev/slinky/inject/ReloadState'},'Unexpected class set change'
  changes=[]
  for c,x in a.items():
   y=b[c];diff={k:[x[k],y[k]] for k in x if k not in ('methods','fields') and x[k]!=y[k]}
   af={f['name']+':'+f['desc']:f for f in x['fields']};bf={f['name']+':'+f['desc']:f for f in y['fields']}
   assert af==bf,('Field metadata changed',c)
   if diff:changes.append({'class':c,'changes':diff})
  with zipfile.ZipFile(origjar) as oz,zipfile.ZipFile(rebuilt) as bz:
   orn={s for s in oz.namelist() if not s.endswith(('.class','/')) and s!='META-INF/MANIFEST.MF'}
   brn={s for s in bz.namelist() if not s.endswith(('.class','/')) and s!='META-INF/MANIFEST.MF'}
   assert orn==brn,('Resource name changes',n,orn^brn)
   assert all(oz.read(s)==bz.read(s) for s in orn),('Resource byte changes',n)
   manifests={}
   for side,z in [('original',oz),('rebuilt',bz)]:
    manifests[side]=dict(line.split(': ',1) for line in z.read('META-INF/MANIFEST.MF').decode().splitlines() if ': ' in line)
   for key in ('Implementation-Title','Implementation-Version'):
    assert manifests['original'].get(key)==manifests['rebuilt'].get(key),('Manifest identity changed',n,key)
  flow=json.loads(out.read_text());methodchanges=[];paired=set();missing=[]
  for row in flow['methods']:
   c,m,bm=row['class'],row['method'],row['rebuilt_method']
   old=a[c]['methods'][m];new=b[c]['methods'].get(bm)
   if new is None:missing.append([c,m]);continue
   paired.add((c,bm))
   diff={k:[old[k],new[k]] for k in old if k not in ('code','handlers') and old[k]!=new[k]}
   # Synthetic helper marker is metadata; all other method access flags,
   # including synchronized/static/native/abstract/strict, affect semantics.
   assert (old['access'] & ~4096)==(new['access'] & ~4096),('Semantic method flags changed',c,m,old['access'],new['access'])
   if diff:methodchanges.append({'class':c,'method':m,'rebuilt_method':bm,'changes':diff})
  added=[[c,m] for c,x in b.items() for m in x['methods'] if (c,m) not in paired]
  assert not missing,('Original methods missing without matched helpers',missing)
  report['variants'][str(n)]={'original_jar_sha256':sha(original),'rebuilt_jar_sha256':sha(rebuilt.read_bytes()),'original_classes':len(a),'rebuilt_classes':len(b),'intentional_new_classes':['dev/slinky/inject/ReloadState'],'intentional_lifecycle_changes':['Bootstrap','ModuleManager.shutdown','ClientEventBridge.install/attachPipeline','SlinkyMod.initialize','Platform.openResource','RenderHooks.install'],'original_methods':sum(len(c['methods']) for c in a.values()),'rebuilt_methods':sum(len(c['methods']) for c in b.values()),'flow_summary':flow['summary'],'class_metadata_changes':changes,'method_metadata_changes':methodchanges,'unpaired_added_methods':added,'unpaired_missing_methods':missing,'semantic_method_flags_equal':True,'original_field_metadata_equal':True,'nonclass_resources_equal':True,'resource_count':len(orn),'manifest_values':manifests}
 (ROOT/'evidence/java-verification.json').write_text(json.dumps(report,indent=2)+'\n')
 print('Verified original specimen identity, all class names/fields/resources, and compared every method in all three variants.')
 print('Unresolved graph matches require the recorded human-style static review; this script is not an equivalence theorem.')

if __name__=='__main__':main()
