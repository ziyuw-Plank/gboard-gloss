import re,gzip,collections,os,xml.etree.ElementTree as ET
OUT='../module/app/src/main/assets/dicts'; os.makedirs(OUT,exist_ok=True)
D=collections.defaultdict(dict)
def quality(g, idx=0):
    gl=g.lower(); score=0.0
    if gl.startswith('to '): score+=8
    if 'mythical' in gl or 'archaic' in gl: score-=50
    if 'surname' in gl: score-=30
    if 'dialect' in gl: score-=15
    score+=min(len(g),40)/20.0; score-=idx*0.5
    return score
def add(s,t,k,v,limit=40,q=None):
    k=k.strip(); v=v.strip()
    if not k or not v or len(k)>40: return
    if s in('en','fr','de','es','ru'): k=k.lower()
    d=D[(s,t)]; v=v[:limit]
    if k not in d: d[k]=v if q is None else (v,q); return
    if q is None: return
    old=d[k]
    if isinstance(old,tuple):
        if q>old[1]: d[k]=(v,q)
    else:
        d[k]=(v,q)
def clean(x): return re.sub(r'\s*\([^)]*\)|\s*\[[^\]]*\]','',x).strip()
# CC-CEDICT
for l in open('../cedict.txt',encoding='utf-8'):
    if l.startswith('#'): continue
    p=l.split(' ',2); s=p[1]; tr=p[0]; gl=[clean(g) for g in l[l.index('/')+1:].strip('/\n').split('/')]
    gl=[g for g in gl if g and not g.startswith('variant of') and not g.startswith('see ') and not g.startswith('CL:')]
    if not gl: continue
    scored=[(g,quality(g,i)) for i,g in enumerate(gl[:5])]
    best,bq=max(scored,key=lambda x:x[1])
    add('zh','en',s,best,48,bq); add('zh','en',tr,best,48,bq)
    for g in gl[:2]:
        g2=re.sub(r'^to ','',g)
        if len(g2.split())<=2: add('en','zh',g2,s,12)
# JMdict
ns='{http://www.w3.org/XML/1998/namespace}lang'
for ev,e in ET.iterparse(gzip.open('JMdict_e.gz')):
    if e.tag!='entry': continue
    ks=[x.text for x in e.findall('k_ele/keb')]; rs=[x.text for x in e.findall('r_ele/reb')]
    gl=[clean(g.text) for g in e.findall('sense/gloss') if g.text]
    if gl:
        for k in ks+rs: add('ja','en',k,gl[0])
        for g in gl[:2]:
            g2=re.sub(r'^to ','',g)
            if len(g2.split())<=2: add('en','ja',g2,(ks or rs)[0],12)
    e.clear()
# FreeDict TEI
T='{http://www.tei-c.org/ns/1.0}'
for name in ['eng-deu','deu-eng','eng-fra','fra-eng','eng-rus','rus-eng','eng-spa','spa-eng']:
    m={'eng':'en','deu':'de','fra':'fr','rus':'ru','spa':'es'}; s,t=[m[x] for x in name.split('-')]
    for ev,e in ET.iterparse(f'{name}/{name}.tei'):
        if e.tag!=T+'entry': continue
        o=e.find(f'{T}form/{T}orth'); q=e.find(f'.//{T}cit/{T}quote')
        if o is not None and q is not None and o.text and q.text: add(s,t,o.text,q.text,24)
        e.clear()
    print(name,len(D[(s,t)]),flush=True)
for (s,t),d in D.items():
    d={k:(v[0] if isinstance(v,tuple) else v) for k,v in d.items()}; keys=sorted(d,key=lambda k:k.encode())
    with gzip.open(f'{OUT}/{s}_{t}.tsv.gz','wb',9) as f:
        f.write(''.join(f'{k}\t{d[k].replace(chr(9)," ")}\n' for k in keys if '\n' not in k and '\t' not in k).encode())
    print(s,t,len(d),os.path.getsize(f'{OUT}/{s}_{t}.tsv.gz'))
