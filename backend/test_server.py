import base64, hashlib, json, os, tempfile, time, unittest, uuid
from unittest.mock import patch
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
import server

class AttendanceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();server.DB=os.path.join(self.tmp.name,'test.db');server.init();self.api=server.API()
        for uid,role in [('s1','student'),('s2','student'),('o1','organiser'),('o2','organiser'),('admin','admin')]:server.add_user(uid,uid,role,'TestPassword123')
        self.key=ec.generate_private_key(ec.SECP256R1());self.other=ec.generate_private_key(ec.SECP256R1())
        self.student=self.login('s1',self.key)['token'];self.org=self.login('o1')['token'];self.admin=self.login('admin')['token']
        self.sid=self.call('POST','/sessions',{'title':'Class','seconds':300},self.org)['id']
        self.beacon=self.call('POST',f'/sessions/{self.sid}/beacon',{},self.org)['beacon']
    def tearDown(self):self.tmp.cleanup()
    def pub(self,key):return base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    def login(self,uid,key=None):return self.call('POST','/login',{'id':uid,'password':'TestPassword123','pub':self.pub(key) if key else ''})
    def call(self,method,path,data=None,token='',key=None,nonce=None):
        raw=json.dumps(data).encode() if data is not None else b'';h={'Authorization':'Bearer '+token}
        if key:
            stamp=str(int(time.time()));nonce=nonce or uuid.uuid4().hex
            text='\n'.join([method,path,stamp,nonce,hashlib.sha256(raw).hexdigest()])
            h.update({'X-Time':stamp,'X-Nonce':nonce,'X-Signature':base64.b64encode(key.sign(text.encode(),ec.ECDSA(hashes.SHA256()))).decode()})
        return self.api.handle(method,path,data or {},h,raw)
    def mark(self,**updates):
        data={'sid':self.sid,'beacon':self.beacon,'samples':[-55,-57,-56,-58,-53]};data.update(updates)
        return self.call('POST','/attendance',data,self.student,self.key)
    def rejects(self,code,fn):
        with self.assertRaises(server.Error) as raised:fn()
        self.assertEqual(code,raised.exception.status)
    def test_checkin_idempotent(self):
        self.mark();self.mark();result=self.call('GET',f'/sessions/{self.sid}',token=self.org)
        self.assertEqual(1,result['present']);self.assertEqual(2,len(result['roster']))
    def test_expired_beacon(self):
        with server.connect() as c:c.execute('UPDATE beacons SET expires=?',(time.time()-1,))
        self.rejects(403,self.mark)
    def test_wrong_token(self):self.rejects(403,lambda:self.mark(beacon='0000000000000000'))
    def test_weak_signal(self):self.rejects(403,lambda:self.mark(samples=[-90]*5))
    def test_too_few_samples(self):self.rejects(400,lambda:self.mark(samples=[-50]*4))
    def test_invalid_rssi(self):self.rejects(400,lambda:self.mark(samples=[True]*5))
    def test_wrong_device_signature(self):self.rejects(403,lambda:self.call('GET','/sessions',token=self.student,key=self.other))
    def test_signature_replay(self):
        nonce=uuid.uuid4().hex;self.call('GET','/sessions',token=self.student,key=self.key,nonce=nonce)
        self.rejects(409,lambda:self.call('GET','/sessions',token=self.student,key=self.key,nonce=nonce))
    def test_closed_session(self):
        self.call('POST',f'/sessions/{self.sid}/close',{},self.org);self.rejects(409,self.mark)
    def test_expired_session(self):
        with server.connect() as c:c.execute('UPDATE sessions SET ends=?',(time.time()-1,))
        self.rejects(409,self.mark)
    def test_one_device_one_account(self):self.rejects(403,lambda:self.login('s2',self.key))
    def test_admin_device_change(self):
        new=self.login('s1',self.other);self.assertTrue(new['device_mismatch'])
        self.call('POST','/device/request',{'pub':self.pub(self.other)},new['token'],self.other)
        self.rejects(404,lambda:self.call('POST','/device/approve',{'id':'s1','approve':True},self.org))
        self.call('POST','/device/approve',{'id':'s1','approve':True},self.admin)
        self.rejects(401,lambda:self.call('GET','/sessions',token=self.student,key=self.key))
        fresh=self.login('s1',self.other);self.assertFalse(fresh['device_mismatch'])
        self.call('GET','/sessions',token=fresh['token'],key=self.other)
    def test_manual_removal_blocks_remark(self):
        self.mark();self.call('POST',f'/sessions/{self.sid}/manual',{'id':'s1','present':False,'reason':'Left before check'},self.org)
        self.rejects(403,self.mark)
    def test_manual_add_and_percentage(self):
        self.call('POST',f'/sessions/{self.sid}/manual',{'id':'s1','present':True,'reason':'Phone unavailable'},self.org)
        self.call('POST',f'/sessions/{self.sid}/close',{},self.org)
        result=self.call('GET','/sessions',token=self.student,key=self.key);self.assertEqual(100,result['percentage'])
    def test_session_owner(self):
        other=self.login('o2')['token'];self.rejects(403,lambda:self.call('GET',f'/sessions/{self.sid}',token=other))
    def test_enrolment_snapshot(self):
        server.add_user('late','Late','student','TestPassword123');late=self.login('late',self.other)['token']
        self.rejects(403,lambda:self.call('POST','/attendance',{'sid':self.sid,'beacon':self.beacon,'samples':[-50]*5},late,self.other))
    def test_csv_safe(self):
        with server.connect() as c:c.execute("UPDATE users SET name='=BAD()' WHERE id='s1'")
        result=self.call('GET',f'/sessions/{self.sid}/csv',token=self.org);self.assertIn("'=BAD()",result)
    def test_student_cannot_create(self):self.rejects(404,lambda:self.call('POST','/sessions',{'title':'Fake','seconds':300},self.student,self.key))
    def test_logout_revokes_token(self):
        self.call('POST','/logout',{},self.student,self.key);self.rejects(401,lambda:self.call('GET','/sessions',token=self.student,key=self.key))
    def test_token_rotation_grace(self):
        original=time.time()
        with patch('server.time.time',return_value=original+9):
            new=self.call('POST',f'/sessions/{self.sid}/beacon',{},self.org)['beacon'];self.assertNotEqual(self.beacon,new);self.mark()
        with patch('server.time.time',return_value=original+13):self.rejects(403,self.mark)
    def test_real_http_transport(self):
        import http.client, threading
        httpd=server.ThreadingHTTPServer(('127.0.0.1',0),server.Handler)
        thread=threading.Thread(target=httpd.serve_forever,daemon=True);thread.start()
        try:
            conn=http.client.HTTPConnection('127.0.0.1',httpd.server_port,timeout=5)
            conn.request('GET','/health');response=conn.getresponse();self.assertEqual(200,response.status);self.assertEqual('ok',json.loads(response.read())['status']);conn.close()
            conn=http.client.HTTPConnection('127.0.0.1',httpd.server_port,timeout=5)
            conn.request('POST','/login',json.dumps({'id':'o1','password':'TestPassword123'}),{'Content-Type':'application/json'})
            response=conn.getresponse();self.assertEqual(200,response.status);self.assertIn('token',json.loads(response.read()));conn.close()
        finally:httpd.shutdown();httpd.server_close();thread.join()
    def test_login_rate_limit(self):
        for _ in range(30):
            try:self.call('POST','/login',{'id':'none','password':'incorrect'})
            except server.Error:pass
        self.rejects(429,lambda:self.login('o1'))

if __name__=='__main__':unittest.main(verbosity=2)
