#!/usr/bin/env python3
"""Persistent, source-grounded writing workspace. No model/network dependencies."""
import argparse
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

CATEGORIES = ('chapter_outline','scene','character','relationship','world','timeline',
              'power','history','faction','place','item','foreshadowing','craft')
KINDS = ('explicit','reported','inference','analysis')
UPDATES = ('new','supplement','change','reveal','conflict')
HEADING = re.compile(r'^\s*(?:第[零〇一二三四五六七八九十百千万两\d、]+[章节回卷].*|chapter\s+\d+.*)\s*$', re.I)

class Store:
    def __init__(self, root):
        self.root = Path(root).resolve()
        workspace = Path('/workspace').resolve()
        if str(root).startswith('/workspace/') and not self.root.is_relative_to(workspace):
            raise ValueError('Project symlink escapes workspace')
        self.root.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.root / 'writing.sqlite3', timeout=20)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sources(id INTEGER PRIMARY KEY,title TEXT NOT NULL,sha TEXT UNIQUE NOT NULL,encoding TEXT NOT NULL,lines INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS chapters(id INTEGER PRIMARY KEY,source INTEGER NOT NULL REFERENCES sources(id),ordinal INTEGER NOT NULL,heading TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,UNIQUE(source,ordinal));
CREATE TABLE IF NOT EXISTS segments(id INTEGER PRIMARY KEY,chapter INTEGER NOT NULL REFERENCES chapters(id),ordinal INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,seen INTEGER NOT NULL DEFAULT 0,UNIQUE(chapter,ordinal));
CREATE INDEX IF NOT EXISTS segments_chapter ON segments(chapter,seen);
CREATE TABLE IF NOT EXISTS entries(id INTEGER PRIMARY KEY,source INTEGER NOT NULL REFERENCES sources(id),category TEXT NOT NULL,entity TEXT NOT NULL,topic TEXT NOT NULL,UNIQUE(source,category,entity,topic));
CREATE TABLE IF NOT EXISTS revisions(id INTEGER PRIMARY KEY,entry INTEGER NOT NULL REFERENCES entries(id),chapter INTEGER NOT NULL REFERENCES chapters(id),payload TEXT NOT NULL,digest TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(entry,chapter,digest));
CREATE INDEX IF NOT EXISTS revisions_chapter ON revisions(chapter);
CREATE TABLE IF NOT EXISTS audits(chapter INTEGER NOT NULL REFERENCES chapters(id),category TEXT NOT NULL,result TEXT NOT NULL,note TEXT NOT NULL,PRIMARY KEY(chapter,category));
CREATE TABLE IF NOT EXISTS completed(chapter INTEGER PRIMARY KEY REFERENCES chapters(id),finished TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS documents(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
''')
        version = self.db.execute("SELECT value FROM meta WHERE key='schema'").fetchone()
        if version and version['value'] != '1':
            raise ValueError('Unsupported writing database schema')
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO meta VALUES('schema','1')")

    def init(self, title):
        title = clean(title, 'title', 200)
        with self.db:
            old = self.db.execute("SELECT value FROM meta WHERE key='title'").fetchone()
            if old and old['value'] != title:
                raise ValueError('Project already exists with a different title')
            self.db.execute("INSERT OR IGNORE INTO meta VALUES('title',?)", (title,))
        return {'project':title,'root':str(self.root),'categories':CATEGORIES}

    def source_lines(self, source):
        row = self.db.execute('SELECT * FROM sources WHERE id=?', (source,)).fetchone()
        if not row: raise ValueError('Source not found')
        raw = (self.root / 'sources' / (row['sha'] + '.txt')).read_bytes()
        if hashlib.sha256(raw).hexdigest() != row['sha']:
            raise ValueError('Immutable source copy changed; restore original before continuing')
        return raw.decode('utf-8').splitlines()

    def chapter(self, chapter):
        row = self.db.execute('SELECT * FROM chapters WHERE id=?', (chapter,)).fetchone()
        if not row: raise ValueError('Chapter ID not found; use ordinal mapping from status')
        return row

    def import_source(self, path, title, encoding='utf-8-sig'):
        if Path(path).stat().st_size > 150 * 1024 * 1024: raise ValueError('Source exceeds 150 MiB')
        raw = Path(path).read_bytes()
        if len(raw) > 150 * 1024 * 1024: raise ValueError('Source exceeds 150 MiB; split before import')
        text = raw.decode(encoding, errors='strict').replace('\r\n','\n').replace('\r','\n')
        lines = text.splitlines()
        if not lines or '\x00' in text: raise ValueError('Empty or binary source')
        encoded = text.encode('utf-8'); sha = hashlib.sha256(encoded).hexdigest()
        existing = self.db.execute('SELECT id FROM sources WHERE sha=?', (sha,)).fetchone()
        if existing: return {'source':existing['id'],'duplicate':True}
        headings = [(n,line.strip()) for n,line in enumerate(lines,1) if HEADING.match(line)]
        # Number labels are display-only: duplicate, merged and skipped labels remain distinct.
        if not headings: headings = [(1, clean(title,'title',200))]
        elif headings[0][0] > 1 and any(s.strip() for s in lines[:headings[0][0]-1]):
            headings.insert(0,(1,'前置文本（需核对，不计入原著章号）'))
        directory = self.root / 'sources'; directory.mkdir(exist_ok=True)
        target = directory / (sha+'.txt')
        target.write_bytes(encoded)
        with self.db:
            cur = self.db.execute('INSERT INTO sources(title,sha,encoding,lines) VALUES(?,?,?,?)',
                                  (clean(title,'title',200),sha,encoding,len(lines)))
            source = cur.lastrowid
            for index,(start,heading) in enumerate(headings):
                end = headings[index+1][0]-1 if index+1<len(headings) else len(lines)
                chapter = self.db.execute('INSERT INTO chapters(source,ordinal,heading,start,end) VALUES(?,?,?,?,?)',
                                          (source,index+1,heading,start,end)).lastrowid
                segstart,size,segno = start,0,1
                for n in range(start,end+1):
                    if n>segstart and size + len(lines[n-1])+1 > 4500:
                        self.db.execute('INSERT INTO segments(chapter,ordinal,start,end) VALUES(?,?,?,?)',
                                        (chapter,segno,segstart,n-1))
                        segstart,size,segno=n,0,segno+1
                    size += len(lines[n-1])+1
                self.db.execute('INSERT INTO segments(chapter,ordinal,start,end) VALUES(?,?,?,?)',
                                (chapter,segno,segstart,end))
        return {'source':source,'chapters':len(headings),'lines':len(lines),'sha256':sha,
                'note':'Review detected headings before reading. Ordinal and original chapter label differ.'}

    def status(self, source, offset=0, limit=30):
        offset=max(0,int(offset));limit=min(max(1,int(limit)),100)
        chapters=[dict(r) for r in self.db.execute('''SELECT c.*,EXISTS(SELECT 1 FROM completed d WHERE d.chapter=c.id) AS complete,
(SELECT COUNT(*) FROM segments s WHERE s.chapter=c.id) AS segments,
(SELECT COUNT(*) FROM segments s WHERE s.chapter=c.id AND s.seen=1) AS delivered
FROM chapters c WHERE c.source=? ORDER BY ordinal''',(source,))]
        if not chapters: raise ValueError('Source not found')
        todo=next((c for c in chapters if not c['complete']),None)
        return {'source':source,'total':len(chapters),'complete':sum(c['complete'] for c in chapters),
                'next':todo,'chapters':chapters[offset:offset+limit],'offset':offset,'list_truncated':len(chapters)>offset+limit,
                'entries':self.db.execute('SELECT COUNT(*) FROM entries WHERE source=?',(source,)).fetchone()[0]}

    def sequential(self, chapter):
        c=self.chapter(chapter)
        previous=self.db.execute('''SELECT id FROM chapters WHERE source=? AND ordinal<?
AND id NOT IN (SELECT chapter FROM completed) ORDER BY ordinal LIMIT 1''',(c['source'],c['ordinal'])).fetchone()
        if previous: raise ValueError('Finish earlier chapter ID '+str(previous['id'])+' first')
        return c

    def read(self, chapter, segment):
        c=self.sequential(chapter)
        s=self.db.execute('SELECT * FROM segments WHERE chapter=? AND ordinal=?',(chapter,segment)).fetchone()
        if not s: raise ValueError('Segment not found')
        lines=self.source_lines(c['source'])
        # Delivery is tracked, not a claim that the model understood the text.
        with self.db: self.db.execute('UPDATE segments SET seen=1 WHERE id=?',(s['id'],))
        return {'chapter':chapter,'heading':c['heading'],'ordinal':c['ordinal'],'segment':segment,
                'source':c['source'],'lines':[{'line':n,'text':lines[n-1]} for n in range(s['start'],s['end']+1)],
                'delivery_only':True}

    def record(self, chapter, items):
        c=self.sequential(chapter)
        if self.db.execute('SELECT 1 FROM completed WHERE chapter=?',(chapter,)).fetchone():
            raise ValueError('Chapter already closed; do not silently mutate completed extraction')
        if not isinstance(items,list) or not 1<=len(items)<=100: raise ValueError('items must contain 1..100 entries')
        lines=self.source_lines(c['source']); validated=[]
        for item in items:
            if not isinstance(item,dict): raise ValueError('Entry must be an object')
            category=choice(item.get('category'),CATEGORIES,'category')
            kind=choice(item.get('kind'),KINDS,'kind')
            if category=='craft' and kind!='analysis': raise ValueError('Craft entries must be marked analysis')
            update=choice(item.get('update'),UPDATES,'update')
            entity=clean(item.get('entity'),'entity',160)
            topic=clean(item.get('topic'),'topic',160)
            claim=clean(item.get('claim'),'claim',2000)
            evidence=item.get('evidence')
            if not isinstance(evidence,list) or not 1<=len(evidence)<=12: raise ValueError('1..12 evidence anchors required')
            anchors=[]
            for anchor in evidence:
                if not isinstance(anchor,dict): raise ValueError('Evidence anchor must be an object')
                line=anchor.get('line');quote=clean(anchor.get('quote'),'quote',600)
                if type(line) is not int or not c['start']<=line<=c['end'] or quote not in lines[line-1]:
                    raise ValueError('Evidence must be an exact quote within current chapter')
                seen=self.db.execute('SELECT 1 FROM segments WHERE chapter=? AND seen=1 AND start<=? AND end>=?',
                                     (chapter,line,line)).fetchone()
                if not seen: raise ValueError('Read evidence segment before recording its claims')
                anchors.append({'line':line,'quote':quote})
            phase=clean(item.get('phase','未明确'),'phase',500)
            links=item.get('links',[])
            if not isinstance(links,list) or len(links)>30 or any(not isinstance(x,str) or len(x)>160 for x in links):
                raise ValueError('links must contain up to 30 short entity names')
            payload=dict(category=category,kind=kind,update=update,entity=entity,topic=topic,claim=claim,
                         evidence=anchors,phase=phase,links=links,verification='quote_matched_semantics_unreviewed')
            validated.append(payload)
        ids=[]
        with self.db:
            for payload in validated:
                key=(c['source'],payload['category'],payload['entity'],payload['topic'])
                old=self.db.execute('SELECT id FROM entries WHERE source=? AND category=? AND entity=? AND topic=?',key).fetchone()
                if not old and payload['update']!='new': raise ValueError('Missing entry; use update=new or exact existing entity/topic')
                if not old:
                    eid=self.db.execute('INSERT INTO entries(source,category,entity,topic) VALUES(?,?,?,?)',key).lastrowid
                else:eid=old['id']
                raw=json.dumps(payload,ensure_ascii=False,sort_keys=True)
                digest=hashlib.sha256(raw.encode()).hexdigest()
                self.db.execute('INSERT OR IGNORE INTO revisions(entry,chapter,payload,digest) VALUES(?,?,?,?)',
                                (eid,chapter,raw,digest))
                ids.append(eid)
            # Any extraction change invalidates the old completeness checklist.
            self.db.execute('DELETE FROM audits WHERE chapter=?',(chapter,))
        return {'entry_ids':ids,'note':'Quotes validated; factual entailment still requires model/human review. Revisions never overwrite history.'}

    def audit(self, chapter, checks):
        self.sequential(chapter)
        if self.db.execute('SELECT 1 FROM completed WHERE chapter=?',(chapter,)).fetchone(): raise ValueError('Chapter already closed')
        if not isinstance(checks,dict) or set(checks)!=set(CATEGORIES): raise ValueError('Audit every category exactly once')
        if self.db.execute('SELECT 1 FROM segments WHERE chapter=? AND seen=0',(chapter,)).fetchone():
            raise ValueError('Deliver every source segment before auditing')
        valid=[]
        for cat,check in checks.items():
            if not isinstance(check,dict):raise ValueError('Audit item must be an object')
            result=choice(check.get('result'),('found','none','uncertain'),'result')
            note=clean(check.get('note'),'note',800)
            found=self.db.execute('''SELECT 1 FROM revisions r JOIN entries e ON e.id=r.entry
WHERE r.chapter=? AND e.category=?''',(chapter,cat)).fetchone()
            if result=='found' and not found: raise ValueError('No entries recorded for '+cat)
            if result=='none' and found: raise ValueError('Cannot declare none when entries exist: '+cat)
            valid.append((chapter,cat,result,note))
        with self.db:
            self.db.executemany('INSERT OR REPLACE INTO audits(chapter,category,result,note) VALUES(?,?,?,?)',valid)
        return {'audited':len(valid),'note':'Audit is an attributed model checklist, not proof of exhaustive extraction.'}

    def finish(self, chapter):
        self.sequential(chapter)
        if self.db.execute('SELECT 1 FROM segments WHERE chapter=? AND seen=0',(chapter,)).fetchone():
            raise ValueError('Unread segments remain')
        audits=self.db.execute('SELECT * FROM audits WHERE chapter=?',(chapter,)).fetchall()
        if len(audits)!=len(CATEGORIES) or any(a['result']=='uncertain' for a in audits):
            raise ValueError('Missing category audits or unresolved uncertain categories')
        if not self.db.execute('''SELECT 1 FROM revisions r JOIN entries e ON e.id=r.entry
WHERE r.chapter=? AND e.category='chapter_outline' ''',(chapter,)).fetchone():
            raise ValueError('Chapter outline required before advancing')
        with self.db:self.db.execute('INSERT OR IGNORE INTO completed(chapter) VALUES(?)',(chapter,))
        return {'chapter':chapter,'complete':True}

    def search(self, source, query='', through=None, limit=30, offset=0):
        limit=min(max(int(limit),1),50);offset=max(int(offset),0)
        if not isinstance(query,str) or len(query)>160:raise ValueError('Query must be short text')
        if not self.db.execute('SELECT 1 FROM sources WHERE id=?',(source,)).fetchone():raise ValueError('Source not found')
        if through is not None:
            c=self.chapter(through)
            if c['source']!=source:raise ValueError('Cutoff belongs to another source')
            cutoff=c['ordinal']
        else: cutoff=2**31-1
        rows=self.db.execute('''SELECT e.id,c.id AS chapter,c.ordinal,c.heading,r.payload,r.id AS revision
FROM entries e JOIN revisions r ON r.entry=e.id JOIN chapters c ON c.id=r.chapter
WHERE e.source=? AND c.ordinal<=? AND (e.entity LIKE ? OR e.topic LIKE ? OR json_extract(r.payload,'$.claim') LIKE ?)
ORDER BY c.ordinal,r.id LIMIT ? OFFSET ?''',(source,cutoff,*(['%'+query+'%']*3),limit+1,offset)).fetchall()
        return {'entries':[dict(r, payload=json.loads(r['payload'])) for r in rows[:limit]],
                'truncated':len(rows)>limit,'next_offset':offset+limit if len(rows)>limit else None,'warning':'Cutoff filters evidence discovery only; phase/knowledge applicability must still be checked. Conflicts and old revisions are intentionally retained.'}

    def browse(self, source, category=None, entity=None, offset=0, limit=20):
        if not self.db.execute("SELECT 1 FROM sources WHERE id=?",(source,)).fetchone():raise ValueError("Source not found")
        if category is not None:choice(category,CATEGORIES,"category")
        offset=max(0,int(offset));limit=min(max(1,int(limit)),50)
        where=["e.source=?"];args=[source]
        if category:where.append("e.category=?");args.append(category)
        if entity:where.append("e.entity LIKE ?");args.append("%"+entity+"%")
        sql="""SELECT e.id,e.category,e.entity,e.topic,r.payload,r.id AS latest_revision,\n(SELECT COUNT(*) FROM revisions x WHERE x.entry=e.id) AS revision_count,\n(SELECT MAX(c2.ordinal) FROM revisions x JOIN chapters c2 ON c2.id=x.chapter WHERE x.entry=e.id) AS last_ordinal\nFROM entries e JOIN revisions r ON r.entry=e.id\nWHERE """ + " AND ".join(where) + """ AND r.id=(SELECT MAX(x2.id) FROM revisions x2 WHERE x2.entry=e.id)\nORDER BY e.category,e.entity,e.id LIMIT ? OFFSET ?"""
        rows=self.db.execute(sql,args+[limit+1,offset]).fetchall()
        counts=self.db.execute("SELECT category,COUNT(*) n FROM entries WHERE source=? GROUP BY category",(source,)).fetchall()
        sql="""SELECT e.entity,e.topic,c.ordinal,r.payload FROM entries e
JOIN revisions r ON r.entry=e.id JOIN chapters c ON c.id=r.chapter
WHERE e.source=? AND json_extract(r.payload,'$.update')='conflict' ORDER BY c.ordinal"""
        conflicts=[dict(r,payload=json.loads(r["payload"])) for r in self.db.execute(sql,(source,)).fetchall()[:20]]
        stats=self.status(source,0,1)
        return {"entries":[dict(r,payload=json.loads(r["payload"])) for r in rows[:limit]],
                "truncated":len(rows)>limit,"next_offset":offset+limit if len(rows)>limit else None,
                "counts_by_category":{r["category"]:r["n"] for r in counts},"conflicts":conflicts,
                "progress":{"chapters_complete":stats["complete"],"chapters_total":stats["total"],
                            "next_chapter":stats["next"],"entries":stats["entries"]},
                "note":"Latest revision only; full history remains. Conflicts are preserved, never auto-resolved."}

    def export(self, source):
        meta=self.db.execute('SELECT * FROM sources WHERE id=?',(source,)).fetchone()
        if not meta:raise ValueError('Source not found')
        folder=self.root/'exports'/str(source);folder.mkdir(parents=True,exist_ok=True)
        entities=self.db.execute('SELECT DISTINCT category,entity FROM entries WHERE source=? ORDER BY category,entity',(source,)).fetchall()
        index=['# '+meta['title']+' · 原著档案','', '原文证据已逐字匹配；条目语义尚未独立核实。阶段与人物认知须结合原文判断。','']
        for entity in entities:
            rows=self.db.execute('''SELECT e.topic,r.payload,c.heading,c.ordinal,c.start,c.end,r.id AS revision
FROM entries e JOIN revisions r ON r.entry=e.id JOIN chapters c ON c.id=r.chapter
WHERE e.source=? AND e.category=? AND e.entity=? ORDER BY c.ordinal,r.id''',(source,entity['category'],entity['entity'])).fetchall()
            ident=hashlib.sha256((entity['category']+'|'+entity['entity']).encode()).hexdigest()[:20]
            file=folder/(entity['category']+'-'+ident+'.md')
            content=['# '+entity['entity'],'','分类：'+entity['category'],'','注意：保留所有修订，不把后期事实自动视为早期角色的认知。','']
            for r in rows:
                v=json.loads(r['payload'])
                content+=['## '+r['topic'],'',v['claim'],'',
                          '来源：'+r['heading']+' · 顺序 '+str(r['ordinal'])+' · 修订 '+str(r['revision']),
                          '性质：'+v['kind']+' / '+v['update']+' · 适用阶段：'+v['phase'],'']
                for ev in v['evidence']:content+=['> 【原文件第'+str(ev['line'])+'行】'+ev['quote'],'']
                if v['links']:content+=['关联：'+'、'.join(v['links']),'']
            file.write_text('\n'.join(content),encoding='utf-8')
            index+=['- ['+entity['category']+' · '+entity['entity'].replace(']','')+']('+file.name+')']
        target=folder/'index.md';target.write_text('\n'.join(index),encoding='utf-8')
        return {'path':str(target),'entity_files':len(entities),'note':'Open markdown files in the existing workspace browser/editor; regenerating exports does not modify database history.'}

    def save(self, kind, title, body):
        choice(kind,('original_setting','outline','scene_plan','draft','decision'),'kind')
        title=clean(title,'title',200);body=clean(body,'body',150000)
        with self.db: rid=self.db.execute('INSERT INTO documents(kind,title,body) VALUES(?,?,?)',(kind,title,body)).lastrowid
        return {'revision':rid,'kind':kind,'title':title,'note':'Append-only revision; not silently promoted to canon.'}

    def documents(self, query=''):
        rows=self.db.execute('SELECT * FROM documents WHERE title LIKE ? ORDER BY id DESC LIMIT 40',('%'+query+'%',)).fetchall()
        return {'documents':[dict(r) for r in rows]}


def clean(value,name,limit):
    if not isinstance(value,str) or not value.strip() or len(value)>limit:raise ValueError(name+' must be nonempty text <= '+str(limit)+' chars')
    return value.strip()

def choice(value,values,name):
    if value not in values:raise ValueError(name+' must be one of '+', '.join(values))
    return value

def dispatch(store, action, data):
    if action=='init':return store.init(data['title'])
    if action=='import':return store.import_source(data['path'],data['title'],data.get('encoding','utf-8-sig'))
    if action=='status':return store.status(int(data['source']),data.get('offset',0),data.get('limit',30))
    if action=='read':return store.read(int(data['chapter']),int(data.get('segment',1)))
    if action=='record':return store.record(int(data['chapter']),data['items'])
    if action=='audit':return store.audit(int(data['chapter']),data['checks'])
    if action=='finish':return store.finish(int(data['chapter']))
    if action=='search':return store.search(int(data['source']),data.get('query',''),int(data['through']) if data.get('through') is not None else None,int(data.get('limit',30)),int(data.get('offset',0)))
    if action=='browse':
        return store.browse(int(data['source']),data.get('category'),data.get('entity'),data.get('offset',0),data.get('limit',20))
    if action=='export':return store.export(int(data['source']))
    if action=='save':return store.save(data['kind'],data['title'],data['body'])
    if action=='documents':return store.documents(data.get('query',''))
    raise ValueError('Unknown action')

def main():
    p=argparse.ArgumentParser();p.add_argument('--project',required=True);p.add_argument('--action',required=True)
    p.add_argument('--json',default=None);p.add_argument('--json-file');args=p.parse_args()
    try:
        if args.json is not None and args.json_file:raise ValueError('Choose --json or --json-file')
        data=json.loads(Path(args.json_file).read_text() if args.json_file else args.json or '{}')
        store=Store(args.project)
        try:result=dispatch(store,args.action,data)
        finally:store.db.close()
        print(json.dumps(result,ensure_ascii=False))
    except (ValueError,KeyError,TypeError,AttributeError,OSError,sqlite3.Error) as e:
        print(json.dumps({'error':str(e)},ensure_ascii=False));sys.exit(1)

if __name__=='__main__':main()
