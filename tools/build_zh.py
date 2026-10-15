# Rebuild zh_en / en_zh from CC-CEDICT (CC BY-SA 4.0) + jieba word frequencies (MIT).
# zh_en line:  key \t sense1; sense2; sense3 \t cost   (cost = segmentation cost, lower = more frequent)
import re,gzip,math,os,collections,sys
CED=sys.argv[1] if len(sys.argv)>1 else '../cedict.txt'; JB=sys.argv[2] if len(sys.argv)>2 else '../jieba_dict.txt'
OUT=os.path.join(os.path.dirname(__file__),'../app/src/main/assets/dicts')
freq={}
for l in open(JB,encoding='utf-8'):
    p=l.split()
    if len(p)>=2: freq[p[0]]=freq.get(p[0],0)+int(p[1])
TOT=sum(freq.values())
BAD=re.compile(r'^(variant of|old variant|archaic variant|see |CL:|surname |abbr\. for|used in |Kangxi radical|also written|also pr\.|erhua variant|Japanese variant|\(old\)|\(archaic\)|\(dialect\)|\(slang\)|\(Internet slang\)|\(Tw\)|\(Taiwan\)|\(Cantonese\)|\(classical\)|\(vulgar\)|radical|\(Buddhism\)|\(Taiwan pr\.)',re.I)
def clean(g):
    g=re.sub(r'\s*\([^)]*\)|\s*\[[^\]]*\]','',g); g=re.sub(r'\S*\|\S*','',g)  # drop parentheticals and 繁|简 refs
    g=re.sub(r'[\u3400-\u9fff]+','',g).strip(' ,;:')
    g=re.sub(r'^fig\.\s*','',g).replace('?','').replace('!','')
    g=re.sub(r'\s+',' ',g).strip()
    return g
entries=collections.defaultdict(list)   # key -> list of (proper, nsenses, senses)
for l in open(CED,encoding='utf-8'):
    if l.startswith('#'): continue
    m=re.match(r'(\S+) (\S+) \[([^\]]*)\] /(.*)/\s*$',l)
    if not m: continue
    tr,s,py,body=m.groups()
    raw=body.split('/')
    senses=[]
    for r in raw:
        if BAD.match(r.strip()) or r.strip().startswith('lit. '): continue
        for part in r.split('; '):
            if BAD.match(part.strip()) or part.strip().startswith('lit. '): continue
            c=clean(part)
            if c and len(c)<=60 and not re.search(r'["()~]',c) and c not in senses: senses.append(c)
    proper=py[:1].isupper()
    if not senses: continue
    for k in {s,tr}: entries[k].append((proper,len(senses),senses,s))
zh={}
en=collections.defaultdict(dict)
for k,es in entries.items():
    low=[e for e in es if not e[0]] or es
    best=max(low,key=lambda e:e[1])   # most senses = main reading
    f=freq.get(best[3],0)
    cost=round(10*math.log(TOT/(f+1)))  # ~ 0..300
    zh[k]=(best[2][:5],cost)
    if k==best[3]:
        for e in low:
            for i,g in enumerate(e[2][:4]):
                g2=re.sub(r'^to ','',g).lower()
                if 0<len(g2.split())<=3:
                    sc=math.log(f+2)-0.4*i-(1.5 if len(k)==1 else 0) - (0 if e is best else 2)
                    if sc>en[g2].get(k,-99): en[g2][k]=sc
os.makedirs(OUT,exist_ok=True)
def w(name,d):
    keys=sorted(d,key=lambda k:k.encode())
    with gzip.open(f'{OUT}/{name}.tsv.gz','wb',9) as f:
        f.write(''.join(f'{k}\t{d[k]}\n' for k in keys if '\t' not in k and '\n' not in k).encode())
    print(name,len(d),os.path.getsize(f'{OUT}/{name}.tsv.gz'))
w('zh_en',{k:'; '.join(v[0]).replace('\t',' ')+'\t'+str(v[1]) for k,v in zh.items()})
w('en_zh',{g:max(c,key=c.get) for g,c in en.items()})
