"""Verify built archive structure and PE resource bytes. Never load/execute targets."""
import pathlib,struct,json,hashlib,zipfile,sys,io
ROOT=pathlib.Path(__file__).resolve().parents[1]
def sha(b):return hashlib.sha256(b).hexdigest()
def pe_resources(data):
 def u16(o):return struct.unpack_from('<H',data,o)[0]
 def u32(o):return struct.unpack_from('<I',data,o)[0]
 assert data[:2]==b'MZ'
 p=u32(60);assert data[p:p+4]==b'PE\0\0'
 machine=u16(p+4);count=u16(p+6);size=u16(p+20);opt=p+24
 assert u16(opt)==0x20b and machine==0x8664,'Expected AMD64 PE32+'
 sections=[]
 for i in range(count):
  s=opt+size+i*40; sections.append((u32(s+12),max(u32(s+8),u32(s+16)),u32(s+20)))
 def offset(rva):
  for start,length,raw in sections:
   if start<=rva<start+length:return raw+rva-start
  raise ValueError('RVA outside image')
 rva=u32(opt+112+2*8);base=offset(rva);out={}
 def walk(relative,path):
  at=base+relative;length=u16(at+12)+u16(at+14)
  for i in range(length):
   ident,target=struct.unpack_from('<II',data,at+16+i*8)
   if ident&0x80000000:
    loc=base+(ident&0x7fffffff);units=u16(loc);key=data[loc+2:loc+2+units*2].decode('utf-16le')
   else:key=ident
   if target&0x80000000:walk(target&0x7fffffff,path+(key,))
   else:
    loc=base+target;start=offset(u32(loc));length=u32(loc+4)
    blob=data[start:start+length];assert len(blob)==length
    out[path+(key,)]=blob
 walk(0,())
 return out
def find_resource(resources,kind,ident):
 matches=[v for k,v in resources.items() if k[:2]==(kind,ident)]
 assert len(matches)==1,(kind,ident)
 return matches[0]

report={'execution':'static only; no target loading or execution','payloads':[],'native':{},'dependencies':{}}
for item in json.loads((ROOT/'deps/download-manifest.json').read_text()):
 path=ROOT/'deps'/item['path'];assert sha(path.read_bytes())==item['sha256'],str(path)
 report['dependencies'][item['path']]=item['sha256']
baseline=json.loads((ROOT/'evidence/original-class-api.json').read_text())
expected=set(baseline) | {'dev/slinky/inject/ReloadState'}
resource_manifest=json.loads((ROOT/'evidence/recovered-resource-manifest.json').read_text())
for n,ns in ((101,'named'),(102,'srg'),(103,'official')):
 path=ROOT/f'build/payload-{n}.jar';data=path.read_bytes()
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  assert z.testzip() is None
  names=z.namelist();assert len(names)==len(set(names))
  classes={name[:-6] for name in names if name.endswith('.class')}
  assert classes==expected,{'missing':sorted(expected-classes),'extra':sorted(classes-expected)}
  assert all(z.read(name)[:4]==b'\xca\xfe\xba\xbe' and struct.unpack_from('>H',z.read(name),6)[0]==52 for name in names if name.endswith('.class'))
  manifest=z.read('META-INF/MANIFEST.MF').decode('utf8');assert 'Implementation-Version: 0.5.0-settings' in manifest
  for r in resource_manifest:
   prefix='java/src/main/resources/'
   if r['path'].startswith(prefix):assert sha(z.read(r['path'][len(prefix):]))==r['sha256'],r['path']
  mapping='dev/slinky/inject/mappings.txt'
  if n==101:assert mapping not in names
  else:assert z.read(mapping)==(ROOT/f'java/mappings/{ns}.txt').read_bytes()
 report['payloads'].append({'id':n,'namespace':ns,'classes':len(classes),'sha256':sha(data),'size':len(data)})

native=ROOT/'build/native/Release';exe=native/'Slinky.exe';dll=native/'SlinkyNative.dll'
if '--java-only' not in sys.argv:
 exebytes=exe.read_bytes();dllbytes=dll.read_bytes();exeres=pe_resources(exebytes);dllres=pe_resources(dllbytes)
 assert find_resource(exeres,10,200)==dllbytes,'Launcher embeds different DLL'
 for n in (101,102,103):assert find_resource(dllres,10,n)==(ROOT/f'build/payload-{n}.jar').read_bytes(),f'Embedded JAR mismatch {n}'
 assert b'asInvoker' in find_resource(exeres,24,1)
 report['native']={'launcher_sha256':sha(exebytes),'dll_sha256':sha(dllbytes),'launcher_size':len(exebytes),'dll_size':len(dllbytes),'resource_200_matches_built_dll':True,'dll_resources_match_rebuilt_jars':True,'manifest':'asInvoker','architecture':'AMD64'}
path=ROOT/'evidence/build-verification.json';path.write_text(json.dumps(report,indent=2)+'\n')
print('Verified three source-built Java 8 payloads, all 509 original classes plus the explicit reload-state helper, manifests, resources, mappings, and pinned dependencies.')
if report['native']:print('Verified AMD64 EXE/DLL and the complete EXE -> DLL -> rebuilt JAR resource chain.')
print('Saved',path)
