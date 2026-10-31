"""Conservative instruction-flow comparison. No sample loading or execution.
Ignores unconditional jump layout, negated branch layout, unused trailing code,
and applies one-to-one local-slot renaming by first encountered instruction.
Partition refinement checks labeled graph bisimulation, including exception
edges. Does not prove equivalence for the unmatched methods.
"""
import json,pathlib,collections,re,sys
ROOT=pathlib.Path(__file__).parent
if __name__=='__main__' and len(sys.argv)==4:
 APATH,BPATH,OUTPATH=map(pathlib.Path,sys.argv[1:])
else:APATH,BPATH,OUTPATH=ROOT/'original-101.json',ROOT/'rebuilt-101.json',ROOT/'normalized-comparison.json'
A=json.loads(APATH.read_text())
B=json.loads(BPATH.read_text())
inverse={154:153,156:155,158:157,160:159,162:161,164:163,166:165,199:198}
branches=set(range(153,167))|{198,199}
localops=set(range(21,26))|set(range(54,59))|{132,169}

# Rename compiler-generated helper references only when a unique body and
# descriptor match exist. Accessors are fingerprinted without own method name.
renames={}; pairs={}
for cn in A.keys()&B.keys():
 am=A[cn]['methods'];bm=B[cn]['methods']
 for name,m in am.items():
  if not (m['access']&4096 or name.startswith('lambda$')):continue
  hits=[(bn,v) for bn,v in bm.items() if (v['access']&4096 or bn.startswith('lambda$')) and name[name.index('('):]==bn[bn.index('('):] and m['code']==v['code'] and m['handlers']==v['handlers']]
  if len(hits)==1:
   bn=hits[0][0];pairs[cn,name]=bn
   if bn!=name:renames[cn,bn[:bn.index('(')],bn[bn.index('('):]]=name[:name.index('(')]

def rename_token(ins,side):
 t=list(ins)
 if side=='rebuilt':
  if t[0] in (182,183,184,185):t[2]=renames.get((t[1],t[2],t[3]),t[2])
  if t[0]==186:
   for n in range(3,len(t)):
    if isinstance(t[n],str) and t[n].startswith('Handle:'):
     z=t[n].split(':');z[3]=renames.get((z[2],z[3],z[4]),z[3]);t[n]=':'.join(z)
 if t[0] in (182,183) and t[2]!='<init>':
  target=A.get(t[1],{}).get('methods',{}).get(t[2]+t[3])
  if target and target['access']&2:t[0]=183
 return t

def argument_slots(desc,access):
 n=0 if access&8 else 1
 args=desc[desc.index('(')+1:desc.index(')')];i=0
 while i<len(args):
  c=args[i];i+=1
  if c=='[':
   while args[i]=='[':i+=1
   if args[i]=='L':i=args.index(';',i)+1
   else:i+=1
   n+=1
  elif c=='L':i=args.index(';',i)+1;n+=1
  else:n+=2 if c in 'JD' else 1
 return n

def graph(m,side,desc):
 code=[rename_token(i,side) for i in m['code']];handlers=m['handlers']
 if not code:return [(['NO_CODE'],[])],0
 slots={i:i for i in range(argument_slots(desc,m['access']))}
 for t in code:
  if t[0] in localops:
   if t[1] not in slots:slots[t[1]]=len(slots)
   t[1]=slots[t[1]]
 def thread(n):
  visited=set()
  while n<len(code) and code[n][0] in (0,167):
   if n in visited:return n
   visited.add(n);n=code[n][1] if code[n][0]==167 else n+1
  return n
 nodes=[]
 for n,t in enumerate(code):
  op=t[0];edges=[];label=t
  if op in branches:
   base=inverse.get(op,op);label=[base]
   edges=[('false' if op==base else 'true',thread(n+1)),('true' if op==base else 'false',thread(t[1]))]
  elif op==167:label=['EPSILON'];edges=[('next',thread(t[1]))]
  elif op==170:
   label=[170,t[1],t[2]];edges=[('default',thread(t[3]))]+[(str(k),thread(target)) for k,target in zip(range(t[1],t[2]+1),t[4:])]
  elif op==171:
   label=[171,t[2]];edges=[('default',thread(t[1]))]+[(str(k),thread(target)) for k,target in zip(t[2],t[3:])]
  elif op not in (172,173,174,175,176,177,191):edges=[('next',thread(n+1))]
  # Preserve catch priority except consecutive entries sharing same range and
  # handler, where changing order cannot change the selected target.
  active=[(ty,thread(target)) for start,end,target,ty in handlers if start<=n<end]
  grouped=[]
  for ty,target in active:
   if grouped and grouped[-1][1]==target:grouped[-1][0].append(ty)
   else:grouped.append(([ty],target))
  edges += [('catch:'+str(k)+':'+','.join(sorted(str(x) for x in types)),target) for k,(types,target) in enumerate(grouped)]
  nodes.append((label,sorted(edges)))
 nodes.append((['FALL_OFF'],[]))
 return nodes,thread(0)

def equivalent(am,bm,desc='()V'):
 ag,ar=graph(am,'original',desc);bg,br=graph(bm,'rebuilt',desc)
 # Restrict to reachable nodes, retaining exception-handler reachability.
 def reach(g,r):
  visited=set();todo=[r]
  while todo:
   n=todo.pop()
   if n in visited:continue
   visited.add(n);todo.extend(t for _,t in g[n][1])
  return visited
 ai=reach(ag,ar);bi=reach(bg,br)
 nodes=[(0,i) for i in sorted(ai)]+[(1,i) for i in sorted(bi)]
 graphs=[ag,bg];parts={};keys={}
 for side,i in nodes:
  k=json.dumps(graphs[side][i][0]);parts[side,i]=keys.setdefault(k,len(keys))
 while True:
  keys={};new={}
  for side,i in nodes:
   label,edges=graphs[side][i];k=(parts[side,i],tuple((kind,parts[side,target]) for kind,target in edges))
   new[side,i]=keys.setdefault(k,len(keys))
  if len(keys)==len(set(parts.values())):return new[0,ar]==new[1,br]
  parts=new

if __name__=='__main__':
 out=[];counts=collections.Counter()
 for cn in sorted(A.keys()&B.keys()):
  am=A[cn]['methods'];bm=B[cn]['methods']
  for mn,m in am.items():
   bn=pairs.get((cn,mn),mn);n=bm.get(bn)
   if n is None:status='missing'
   elif m['code']==n['code'] and m['handlers']==n['handlers']:status='exact_body'
   elif equivalent(m,n,mn):status='flow_equivalent'
   else:status='unresolved'
   counts[status]+=1;out.append({'class':cn,'method':mn,'rebuilt_method':bn,'status':status})
 OUTPATH.write_text(json.dumps({'summary':dict(counts),'methods':out,'helper_renames':{str(k):v for k,v in renames.items()}},indent=2))
 print(dict(counts));print('helper renames',len(renames))
 print('unresolved packages',collections.Counter(x['class'].split('/')[2] for x in out if x['status']=='unresolved'))
