"""End-to-end checks against the Java server, with a disposable database only."""
import concurrent.futures
import contextlib
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

class JavaBackendTest(unittest.TestCase):
    def test_complete_workflow(self):
        java = os.environ.get('JAVA_EXE') or shutil.which('java')
        self.assertTrue(java, 'Set JAVA_EXE to Java 21 or newer')
        with tempfile.TemporaryDirectory(prefix='examflow-java-') as folder:
            data = Path(folder)
            with socket.socket() as s:
                s.bind(('127.0.0.1', 0))
                port = s.getsockname()[1]
            base = f'http://127.0.0.1:{port}'
            env = dict(os.environ, PORT=str(port), EXAMFLOW_DATA=str(data))
            log = open(data / 'server.log', 'w')
            process = None

            def start():
                nonlocal process
                process = subprocess.Popen([java, '-cp', 'build/ExamFlow-2.1.jar;lib/*', 'examflow.ExamFlow'], cwd=ROOT, env=env, stdout=log, stderr=log, creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                for _ in range(100):
                    if process.poll() is not None:
                        log.flush()
                        raise AssertionError((data/'server.log').read_text())
                    try:
                        if request('/health')[0] == 200: return
                    except OSError: pass
                    time.sleep(.1)
                raise AssertionError('Java server did not become ready')

            def stop():
                if process and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)

            def request(path, body=None, cookie=None, origin=None):
                headers = {'Content-Type':'application/json'}
                if cookie: headers['Cookie'] = cookie
                if origin: headers['Origin'] = origin
                req = urllib.request.Request(base+'/api'+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
                try: response=urllib.request.urlopen(req,timeout=15)
                except urllib.error.HTTPError as e: response=e
                with response:
                    return response.status, json.load(response), (response.headers.get('Set-Cookie') or '').split(';')[0]

            try:
                start()
                self.assertEqual(request('/health')[1]['backend'],'Java')
                self.assertEqual(request('/dashboard')[0],401)
                self.assertEqual(request('/admin/accounts')[0],401)
                self.assertEqual(request('/admin/accounts',{})[0],401)
                student={'name':'Test Student','email':'student@example.test','password':'test-password-2026'}
                self.assertEqual(request('/register',dict(student,role='admin'))[0],201)
                self.assertEqual(request('/register',student)[0],409)
                self.assertEqual(request('/login',dict(student,password='incorrect'))[0],401)
                s=request('/login',student)[2]
                self.assertEqual(request('/me',cookie=s)[1]['user']['role'],'student')
                self.assertEqual(request('/exams',{'title':'Not allowed'},s)[0],403)
                self.assertEqual(request('/admin/accounts',cookie=s)[0],403)
                self.assertEqual(request('/admin/accounts',{},s)[0],403)
                self.assertEqual(request('/logout',{},s,'https://outside.example')[0],403)
                admin_password=(data/'admin-access.txt').read_text().split('Administrator password: ')[1].splitlines()[0]
                admin_login=request('/login',{'email':'admin@examflow.local','password':admin_password})
                self.assertEqual(admin_login[0],200)
                self.assertEqual(admin_login[1]['user']['role'],'admin')
                admin=admin_login[2]
                new_admin={'name':'New Administrator','email':'new-admin@example.test','password':'New-admin-password-2026','confirmPassword':'New-admin-password-2026','currentPassword':admin_password,'acceptConditions':True}
                self.assertEqual(request('/admin/accounts',new_admin,admin,'https://outside.example')[0],403)
                self.assertEqual(request('/admin/accounts',dict(new_admin,currentPassword='incorrect'),admin)[0],403)
                self.assertEqual(request('/admin/accounts',dict(new_admin,acceptConditions=False),admin)[0],400)
                self.assertEqual(request('/admin/accounts',dict(new_admin,acceptConditions='true'),admin)[0],400)
                self.assertEqual(request('/admin/accounts',dict(new_admin,confirmPassword='different-password'),admin)[0],400)
                self.assertEqual(request('/admin/accounts',dict(new_admin,password='short',confirmPassword='short'),admin)[0],400)
                self.assertEqual(request('/admin/accounts',dict(new_admin,email=student['email']),admin)[0],409)
                created=request('/admin/accounts',new_admin,admin)
                self.assertEqual(created[0],201)
                self.assertEqual(created[2],'', 'Creating an admin must not replace the creator session')
                self.assertNotIn('password',created[1]['account'])
                self.assertEqual(request('/admin/accounts',new_admin,admin)[0],409)
                accounts=request('/admin/accounts',cookie=admin)[1]['accounts']
                self.assertEqual(len(accounts),2)
                self.assertTrue(all('password' not in a for a in accounts))
                self.assertEqual(accounts[1]['created_by'],'Exam Administrator')
                self.assertEqual(request('/login',{'email':new_admin['email'],'password':new_admin['password']})[1]['user']['role'],'admin')
                self.assertEqual(request('/me',cookie=admin)[1]['user']['email'],'admin@examflow.local')
                with contextlib.closing(sqlite3.connect(data/'examflow.sqlite')) as db:
                    stored=db.execute('SELECT password FROM users WHERE email=?',(new_admin['email'],)).fetchone()[0]
                    self.assertTrue(stored.startswith('pbkdf2:'))
                    self.assertEqual(db.execute('SELECT COUNT(*) FROM admin_account_events').fetchone()[0],1)
                test_exam={'title':'Java integration test','subject':'Testing','description':'Temporary test assessment','duration':1,'published':True,'questions':[{'prompt':'2 + 2?','options':['1','2','3','4'],'answer':3},{'prompt':'3 - 1?','options':['1','2','3','4'],'answer':1}]}
                exam=request('/exams',test_exam,admin)
                self.assertEqual(exam[0],201)
                eid=exam[1]['id']
                attempt=request(f'/exams/{eid}/start',{},s)[1]
                aid=attempt['attempt']['id']
                self.assertNotIn('answer',attempt['questions'][0])
                self.assertEqual(request(f'/exams/{eid}/start',{},s)[1]['attempt']['id'],aid)
                q1,q2=[str(q['id']) for q in attempt['questions']]
                self.assertEqual(request(f'/attempts/{aid}/save',{'answers':{'999999':0}},s)[0],400)
                self.assertEqual(request(f'/attempts/{aid}/save',{'answers':{q1:1.5}},s)[0],400)
                answers={q1:3,q2:0}
                self.assertEqual(request(f'/attempts/{aid}/save',{'answers':answers},s)[0],200)
                stop(); start()
                self.assertEqual(json.loads(request(f'/attempts/{aid}',cookie=s)[1]['attempt']['answers']),answers)
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    results=list(pool.map(lambda _:request(f'/attempts/{aid}/submit',{'answers':answers},s),range(2)))
                self.assertTrue(all(r[1]['attempt']['score']==1 for r in results))
                self.assertEqual(request(f'/attempts/{aid}/submit',{'answers':{}},s)[1]['attempt']['score'],1)
                self.assertEqual(request(f'/attempts/{aid}',cookie=s)[1]['questions'][0]['answer'],3)
                other={'name':'Other Student','email':'other@example.test','password':'other-password-2026'}
                request('/register',other)
                o=request('/login',other)[2]
                self.assertEqual(request(f'/attempts/{aid}',cookie=o)[0],404)
                other_attempt=request(f'/exams/{eid}/start',{},o)[1]['attempt']['id']
                request(f'/attempts/{other_attempt}/save',{'answers':{q1:3}},o)
                with contextlib.closing(sqlite3.connect(data/'examflow.sqlite')) as db, db:
                    db.execute('UPDATE attempts SET deadline=? WHERE id=?',(int(time.time()*1000)-1000,other_attempt))
                time.sleep(1.2)
                with contextlib.closing(sqlite3.connect(data/'examflow.sqlite')) as db, db:
                    row=db.execute('SELECT submitted,score FROM attempts WHERE id=?',(other_attempt,)).fetchone()
                self.assertIsNotNone(row[0], 'Scheduler must finalize without a browser request')
                self.assertEqual(row[1],1)
                self.assertEqual(request(f'/attempts/{other_attempt}/submit',{'answers':{q1:0}},o)[1]['attempt']['score'],1)
                request(f'/exams/{eid}/visibility',{'published':False},admin)
                self.assertFalse(any(e['id']==eid for e in request('/dashboard',cookie=s)[1]['exams']))
                self.assertEqual(request(f'/exams/{eid}/start',{},s)[0],404)
                # Legacy password and persisted session from the original Node backend.
                salt='0123456789abcdef0123456789abcdef'
                legacy_password='old-password-2026'
                legacy_hash=salt+':'+hashlib.scrypt(legacy_password.encode(),salt=salt.encode(),n=16384,r=8,p=1,dklen=64,maxmem=64*1024*1024).hex()
                with contextlib.closing(sqlite3.connect(data/'examflow.sqlite')) as db, db:
                    legacy_id=db.execute('INSERT INTO users(name,email,password,role) VALUES(?,?,?,?)',('Legacy Student','legacy@example.test',legacy_hash,'student')).lastrowid
                    db.execute('INSERT INTO sessions(token,user_id,expires) VALUES(?,?,?)',('legacy-session',legacy_id,int(time.time()*1000)+100000))
                self.assertEqual(request('/me',cookie='examflow=legacy-session')[1]['user']['id'],legacy_id)
                self.assertEqual(request('/login',{'email':'legacy@example.test','password':legacy_password})[0],200)
                with contextlib.closing(sqlite3.connect(data/'examflow.sqlite')) as db, db:
                    updated=db.execute('SELECT password FROM users WHERE id=?',(legacy_id,)).fetchone()[0]
                self.assertTrue(updated.startswith('pbkdf2:'))
                self.assertEqual(request('/dashboard',cookie=admin)[1]['students'].__len__(),3)
                self.assertEqual(request('/logout',{},s)[0],200)
                self.assertEqual(request('/dashboard',cookie=s)[0],401)
                with urllib.request.urlopen(base) as page:
                    self.assertIn('ExamFlow',page.read().decode())
                blocked_payload=dict(new_admin,email='blocked@example.test',currentPassword='incorrect')
                for _ in range(5):
                    self.assertEqual(request('/admin/accounts',blocked_payload,admin)[0],403)
                self.assertEqual(request('/admin/accounts',dict(new_admin,email='blocked@example.test'),admin)[0],429)
                self.assertEqual(len(request('/admin/accounts',cookie=admin)[1]['accounts']),2)
                print('PASS: restricted admin creation, consent, password confirmation, throttling, audit records, role isolation and exam workflow')
            finally:
                stop()
                log.close()

if __name__=='__main__': unittest.main(verbosity=2)
