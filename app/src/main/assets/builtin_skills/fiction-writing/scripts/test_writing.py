import copy
import tempfile
import unittest
from pathlib import Path
from writing import Store, CATEGORIES

class WritingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.path=self.root/'source.txt'
        self.path.write_text('第001章 夜\n沈照守着钟楼。\n第001章 晨\n沈照离开钟楼。\n第003章 雨\n他回来了。\n')
        self.store=Store(self.root/'project');self.store.init('测试作品')
        self.sid=self.store.import_source(self.path,'测试原作')['source']
        self.cs=[r['id'] for r in self.store.db.execute('SELECT id FROM chapters ORDER BY ordinal')]
    def tearDown(self):
        self.store.db.close();self.temp.cleanup()
    def item(self, category='character', chapter=0):
        return dict(category=category,entity='沈照',topic='所在地点',claim='沈照守着钟楼。' if chapter==0 else '沈照离开钟楼。',
                    kind='explicit',update='new' if chapter==0 else 'change',phase='当前场景',links=['钟楼'],
                    evidence=[dict(line=2 if chapter==0 else 4,quote='沈照守着钟楼。' if chapter==0 else '沈照离开钟楼。')])
    def close_first(self):
        self.store.read(self.cs[0],1)
        self.store.record(self.cs[0],[self.item(),self.item('chapter_outline')])
        checks={c:dict(result='found' if c in ('character','chapter_outline') else 'none',note='已回看全文') for c in CATEGORIES}
        self.store.audit(self.cs[0],checks);self.store.finish(self.cs[0])
    def test_duplicate_heading_and_import_idempotency(self):
        self.assertEqual(len(self.cs),3)
        self.assertTrue(self.store.import_source(self.path,'测试原作')['duplicate'])
    def test_order_and_unread_guard(self):
        with self.assertRaisesRegex(ValueError,'earlier'):self.store.read(self.cs[1],1)
        with self.assertRaisesRegex(ValueError,'Read evidence'):self.store.record(self.cs[0],[self.item()])
        with self.assertRaisesRegex(ValueError,'Unread'):self.store.finish(self.cs[0])
    def test_forgery_atomicity_and_retry(self):
        self.store.read(self.cs[0],1)
        forged=self.item();forged['evidence'][0]['quote']='原文没有的话'
        with self.assertRaises(ValueError):self.store.record(self.cs[0],[self.item(),forged])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM entries').fetchone()[0],0)
        self.store.record(self.cs[0],[self.item()]);self.store.record(self.cs[0],[self.item()])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM revisions').fetchone()[0],1)
    def test_revisions_cutoff_and_resume(self):
        self.close_first();self.store.read(self.cs[1],1);self.store.record(self.cs[1],[self.item(chapter=1)])
        early=self.store.search(self.sid,'沈照',through=self.cs[0])
        self.assertTrue(all(x['chapter']==self.cs[0] for x in early['entries']))
        both=self.store.search(self.sid,'沈照')
        self.assertEqual(len([x for x in both['entries'] if x['payload']['category']=='character']),2)
        self.store.db.close();self.store=Store(self.root/'project')
        self.assertEqual(self.store.status(self.sid)['next']['id'],self.cs[1])
    def test_audit_uncertain_cannot_finish(self):
        self.store.read(self.cs[0],1);self.store.record(self.cs[0],[self.item('chapter_outline')])
        checks={c:dict(result='found' if c=='chapter_outline' else 'none',note='逐类核对') for c in CATEGORIES}
        checks['history']['result']='uncertain'
        self.store.audit(self.cs[0],checks)
        with self.assertRaisesRegex(ValueError,'uncertain'):self.store.finish(self.cs[0])
    def test_source_tamper_and_draft_history(self):
        self.store.save('draft','第一章','初稿');self.store.save('draft','第一章','改稿')
        self.assertEqual(len(self.store.documents('第一章')['documents']),2)
        next((self.root/'project'/'sources').iterdir()).write_text('替换过的原文')
        with self.assertRaisesRegex(ValueError,'changed'):self.store.read(self.cs[0],1)

if __name__=='__main__':unittest.main()
