"""ProxyProof reference API. SQLite + signed student requests; no cloud account needed."""
import argparse, base64, csv, getpass, hashlib, hmac, io, json, os, secrets, sqlite3, statistics, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

DB = os.environ.get('PROXYPROOF_DB', 'proxyproof.db')
SCHEMA = '''
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL,password TEXT NOT NULL,pub TEXT UNIQUE);
CREATE TABLE IF NOT EXISTS auth(token TEXT PRIMARY KEY,uid TEXT,expires REAL);
CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,owner TEXT,started REAL,ends REAL,closed INTEGER DEFAULT 0,threshold INTEGER);
CREATE TABLE IF NOT EXISTS members(sid INTEGER,uid TEXT,PRIMARY KEY(sid,uid));
CREATE TABLE IF NOT EXISTS beacons(sid INTEGER,token TEXT,expires REAL,PRIMARY KEY(sid,token));
CREATE TABLE IF NOT EXISTS attendance(sid INTEGER,uid TEXT,present INTEGER,source TEXT,at REAL,rssi REAL,reason TEXT,PRIMARY KEY(sid,uid));
CREATE TABLE IF NOT EXISTS requests(uid TEXT PRIMARY KEY,pub TEXT,at REAL);
CREATE TABLE IF NOT EXISTS nonces(uid TEXT,nonce TEXT,at REAL,PRIMARY KEY(uid,nonce));
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,at REAL,actor TEXT,action TEXT,details TEXT);
CREATE TABLE IF NOT EXISTS attempts(key TEXT PRIMARY KEY,count INTEGER,until REAL);
'''
class Error(Exception):
    def __init__(self, status, message): self.status, self.message = status, message

def connect():
    c=sqlite3.connect(DB, timeout=15); c.row_factory=sqlite3.Row; return c

def init():
    with connect() as c: c.executescript(SCHEMA)

def password_hash(password, salt=None):
    salt=salt or secrets.token_hex(16)
    return salt+':'+hashlib.scrypt(password.encode(),salt=salt.encode(),n=16384,r=8,p=1).hex()

def add_user(uid,name,role,password):
    if role not in ('student','organiser','admin') or len(password)<10: raise ValueError('Role invalid or password shorter than 10 characters')
    with connect() as c: c.execute('INSERT INTO users VALUES(?,?,?,?,NULL)',(uid,name,role,password_hash(password)))

def key_valid(value):
    try:
        key=serialization.load_der_public_key(base64.b64decode(value,validate=True))
        if not isinstance(key,ec.EllipticCurvePublicKey) or key.curve.name!='secp256r1': raise ValueError()
        return key
    except Exception: raise Error(400,'Invalid P-256 public key')

def audit(c,uid,action,data): c.execute('INSERT INTO audit(at,actor,action,details) VALUES(?,?,?,?)',(time.time(),uid,action,json.dumps(data)))
def clean_csv(value):
    text=str(value)
    return "'"+text if text.startswith(('=','+','-','@','\t','\r')) else text

class API:
    def handle(self,method,path,data,headers,raw,peer='local'):
        with connect() as c:
            c.execute('BEGIN IMMEDIATE')
            return self.route(c,method,path,data,headers,raw,peer)
    def route(self,c,method,path,d,h,raw,peer):
        now=time.time()
        if path=='/health' and method=='GET': return {'status':'ok','service':'ProxyProof'}
        if path=='/login' and method=='POST':
            uid=str(d.get('id',''))[:80]; password=str(d.get('password',''))
            # Count all attempts, including unknown accounts, with a durable per-IP window.
            attempt=c.execute('SELECT * FROM attempts WHERE key=?',(peer,)).fetchone()
            if attempt and attempt['until']>now and attempt['count']>=30: raise Error(429,'Too many login attempts; wait 5 minutes')
            count=attempt['count']+1 if attempt and attempt['until']>now else 1
            until=attempt['until'] if attempt and attempt['until']>now else now+300
            c.execute('INSERT OR REPLACE INTO attempts VALUES(?,?,?)',(peer,count,until)); c.commit(); c.execute('BEGIN IMMEDIATE')
            u=c.execute('SELECT * FROM users WHERE id=?',(uid,)).fetchone()
            stored=u['password'] if u else '00000000000000000000000000000000:invalid'
            correct=hmac.compare_digest(password_hash(password,stored.split(':')[0]),stored)
            if not u or not correct: raise Error(401,'Invalid ID or password')
            mismatch=False
            if u['role']=='student':
                pub=d.get('pub',''); key_valid(pub)
                bound=c.execute('SELECT id FROM users WHERE pub=?',(pub,)).fetchone()
                if bound and bound['id']!=uid: raise Error(403,'This device is already bound to another student')
                if not u['pub']:
                    c.execute('UPDATE users SET pub=? WHERE id=?',(pub,uid)); audit(c,uid,'device_bound',{})
                elif u['pub']!=pub: mismatch=True
            token=secrets.token_urlsafe(32)
            c.execute('INSERT INTO auth VALUES(?,?,?)',(hashlib.sha256(token.encode()).hexdigest(),uid,now+43200))
            c.execute('DELETE FROM auth WHERE expires<?',(now,))
            return {'token':token,'id':uid,'name':u['name'],'role':u['role'],'device_mismatch':mismatch}
        token=h.get('Authorization','').removeprefix('Bearer ')
        u=c.execute('SELECT users.* FROM auth JOIN users ON uid=users.id WHERE token=? AND expires>?',(hashlib.sha256(token.encode()).hexdigest(),now)).fetchone()
        if not u: raise Error(401,'Please log in again')
        uid=u['id']; role=u['role']
        if path=='/logout' and method=='POST':
            c.execute('DELETE FROM auth WHERE token=?',(hashlib.sha256(token.encode()).hexdigest(),)); return {'ok':True}
        if path=='/device/request' and method=='POST' and role=='student':
            pub=d.get('pub',''); key_valid(pub)
            self.signature(c,u,method,path,h,raw,pub)
            bound=c.execute('SELECT id FROM users WHERE pub=?',(pub,)).fetchone()
            if bound and bound['id']!=uid: raise Error(403,'Device belongs to another student')
            c.execute('INSERT OR REPLACE INTO requests VALUES(?,?,?)',(uid,pub,now)); audit(c,uid,'device_change_requested',{})
            return {'ok':True,'message':'Admin approval requested'}
        if role=='student': self.signature(c,u,method,path,h,raw,u['pub'])
        if path=='/device/requests' and method=='GET' and role=='admin':
            return {'requests':[dict(r) for r in c.execute('SELECT requests.uid,users.name,requests.pub,requests.at FROM requests JOIN users ON uid=users.id')]}
        if path=='/device/approve' and method=='POST' and role=='admin':
            target=d.get('id'); r=c.execute('SELECT * FROM requests WHERE uid=?',(target,)).fetchone()
            if not r: raise Error(404,'Request not found')
            if d.get('approve') is True:
                bound=c.execute('SELECT id FROM users WHERE pub=? AND id!=?',(r['pub'],target)).fetchone()
                if bound: raise Error(409,'Device already bound to another account')
                c.execute('UPDATE users SET pub=? WHERE id=?',(r['pub'],target)); c.execute('DELETE FROM auth WHERE uid=?',(target,))
            c.execute('DELETE FROM requests WHERE uid=?',(target,)); audit(c,uid,'device_change_reviewed',d)
            return {'ok':True}
        if path=='/sessions' and method=='POST' and role in ('organiser','admin'):
            title=str(d.get('title','')).strip()[:100]; seconds=int(d.get('seconds',300)); threshold=int(d.get('threshold',-70))
            if not title or not 30<=seconds<=3600 or not -95<=threshold<=-30: raise Error(400,'Check title, duration (30–3600s), and RSSI (-95 to -30)')
            if c.execute('SELECT id FROM sessions WHERE owner=? AND closed=0 AND ends>?',(uid,now)).fetchone(): raise Error(409,'Close your active session first')
            cur=c.execute('INSERT INTO sessions(title,owner,started,ends,threshold) VALUES(?,?,?,?,?)',(title,uid,now,now+seconds,threshold)); sid=cur.lastrowid
            c.execute("INSERT INTO members SELECT ?,id FROM users WHERE role='student'",(sid,)); audit(c,uid,'session_started',{'sid':sid})
            return {'id':sid,'ends':now+seconds}
        if path=='/sessions' and method=='GET':
            if role=='student': rows=c.execute('SELECT s.*,COALESCE(a.present,0) present FROM sessions s JOIN members m ON m.sid=s.id LEFT JOIN attendance a ON a.sid=s.id AND a.uid=m.uid WHERE m.uid=? ORDER BY s.id DESC',(uid,)).fetchall()
            elif role=='admin': rows=c.execute('SELECT * FROM sessions ORDER BY id DESC').fetchall()
            else: rows=c.execute('SELECT * FROM sessions WHERE owner=? ORDER BY id DESC',(uid,)).fetchall()
            items=[dict(r) for r in rows]
            for i in items: i['active']=not i['closed'] and i['ends']>now
            closed=[i for i in items if not i['active']]
            return {'sessions':items,'percentage':round(100*sum(i.get('present',0) for i in closed)/len(closed),1) if closed else None,'completed':len(closed)}
        if path=='/attendance' and method=='POST' and role=='student':
            sid=int(d.get('sid',0)); s=self.session(c,sid)
            if s['closed'] or now>=s['ends']: raise Error(409,'Session closed')
            if not c.execute('SELECT 1 FROM members WHERE sid=? AND uid=?',(sid,uid)).fetchone(): raise Error(403,'Not enrolled in this session')
            beacon=c.execute('SELECT 1 FROM beacons WHERE sid=? AND token=? AND expires>?',(sid,str(d.get('beacon','')),now)).fetchone()
            if not beacon: raise Error(403,'Beacon expired or invalid')
            samples=d.get('samples',[])
            if not isinstance(samples,list) or not 5<=len(samples)<=20 or any(type(x)!=int or not -127<=x<=-1 for x in samples): raise Error(400,'At least 5 valid RSSI samples required')
            rssi=statistics.median(samples)
            if rssi<s['threshold']: raise Error(403,'Signal too weak; move closer to organiser')
            old=c.execute('SELECT * FROM attendance WHERE sid=? AND uid=?',(sid,uid)).fetchone()
            if old and old['source']=='manual' and not old['present']: raise Error(403,'Attendance removed by organiser; ask them to review')
            if not old:
                c.execute('INSERT INTO attendance VALUES(?,?,1,?,?,?,?)',(sid,uid,'ble',now,rssi,'')); audit(c,uid,'ble_attendance',{'sid':sid,'rssi':rssi})
            return {'ok':True,'message':'Attendance marked','sid':sid}
        parts=path.strip('/').split('/')
        if len(parts)>=2 and parts[0]=='sessions' and role in ('organiser','admin'):
            sid=int(parts[1]); s=self.session(c,sid)
            if s['owner']!=uid and role!='admin': raise Error(403,'This session belongs to another organiser')
            op=parts[2] if len(parts)==3 else ''
            if op=='beacon' and method=='POST':
                if s['closed'] or s['ends']<=now: raise Error(409,'Session closed')
                if s['owner']!=uid: raise Error(403,'Only the session owner can broadcast')
                latest=c.execute('SELECT * FROM beacons WHERE sid=? ORDER BY expires DESC LIMIT 1',(sid,)).fetchone()
                if latest and latest['expires']>now+4: tok=latest['token']
                else:
                    tok=secrets.token_hex(8); c.execute('INSERT INTO beacons VALUES(?,?,?)',(sid,tok,min(now+12,s['ends'])))
                c.execute('DELETE FROM beacons WHERE expires<?',(now-30,))
                return {'sid':sid,'beacon':tok,'ends':s['ends']}
            if op=='close' and method=='POST':
                c.execute('UPDATE sessions SET closed=1 WHERE id=?',(sid,)); c.execute('DELETE FROM beacons WHERE sid=?',(sid,)); audit(c,uid,'session_closed',{'sid':sid}); return {'ok':True}
            if op=='manual' and method=='POST':
                target=d.get('id'); reason=str(d.get('reason','')).strip()[:250]
                if not reason or type(d.get('present'))!=bool: raise Error(400,'A reason and attendance status are required')
                if not c.execute('SELECT 1 FROM members WHERE sid=? AND uid=?',(sid,target)).fetchone(): raise Error(404,'Student not in roster')
                c.execute('INSERT OR REPLACE INTO attendance VALUES(?,?,?,?,?,?,?)',(sid,target,int(d['present']),'manual',now,None,reason)); audit(c,uid,'manual_attendance',{'sid':sid,**d}); return {'ok':True}
            if op in ('','csv') and method=='GET':
                roster=[dict(r) for r in c.execute('SELECT u.id,u.name,COALESCE(a.present,0) present,a.source,a.at,a.rssi,a.reason FROM members m JOIN users u ON u.id=m.uid LEFT JOIN attendance a ON a.sid=m.sid AND a.uid=m.uid WHERE m.sid=? ORDER BY u.id',(sid,))]
                if op=='csv':
                    out=io.StringIO(); w=csv.writer(out); w.writerow(['Student ID','Name','Present','Source','Timestamp UTC','RSSI','Reason'])
                    for r in roster: w.writerow([clean_csv(r['id']),clean_csv(r['name']),r['present'],r['source'] or '',time.strftime('%Y-%m-%d %H:%M:%S',time.gmtime(r['at'])) if r['at'] else '',r['rssi'] or '',clean_csv(r['reason'] or '')])
                    return out.getvalue()
                return {'session':dict(s),'roster':roster,'present':sum(r['present'] for r in roster),'active':not s['closed'] and now<s['ends']}
        raise Error(404,'Endpoint not found or role not permitted')
    def session(self,c,sid):
        s=c.execute('SELECT * FROM sessions WHERE id=?',(sid,)).fetchone()
        if not s: raise Error(404,'Session not found')
        return s
    def signature(self,c,u,method,path,h,raw,pub):
        try:
            stamp=h.get('X-Time',''); nonce=h.get('X-Nonce',''); now=time.time()
            if abs(now-int(stamp))>30 or not 16<=len(nonce)<=80: raise ValueError()
            canonical='\n'.join([method,path,stamp,nonce,hashlib.sha256(raw).hexdigest()])
            key_valid(pub).verify(base64.b64decode(h.get('X-Signature',''),validate=True),canonical.encode(),ec.ECDSA(hashes.SHA256()))
        except Exception: raise Error(403,'Device signature invalid; check device binding and phone clock')
        c.execute('DELETE FROM nonces WHERE at<?',(now-60,))
        try: c.execute('INSERT INTO nonces VALUES(?,?,?)',(u['id'],nonce,now))
        except sqlite3.IntegrityError: raise Error(409,'Replayed request')

class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.0'
    def do_GET(self): self.respond()
    def do_POST(self): self.respond()
    def respond(self):
        try:
            self.connection.settimeout(15)
            size=int(self.headers.get('Content-Length','0'))
            if size<0 or size>16384: raise Error(413,'Request too large')
            raw=self.rfile.read(size) if size else b''
            data=json.loads(raw) if raw else {}
            if not isinstance(data,dict): raise Error(400,'JSON object required')
            value=API().handle(self.command,self.path,data,self.headers,raw,self.client_address[0])
            code=200; mime='text/csv; charset=utf-8' if isinstance(value,str) else 'application/json'
            body=(value if isinstance(value,str) else json.dumps(value)).encode()
        except Error as e: code=e.status; mime='application/json'; body=json.dumps({'error':e.message}).encode()
        except (ValueError,TypeError,KeyError): code=400; mime='application/json'; body=b'{"error":"Invalid input"}'
        except Exception:
            import traceback; traceback.print_exc(); code=500; mime='application/json'; body=b'{"error":"Internal server error"}'
        self.send_response(code); self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(body))); self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(body)
    def log_message(self,fmt,*args): pass # Avoid logging tokens or student data.

if __name__=='__main__':
    p=argparse.ArgumentParser(); sub=p.add_subparsers(dest='command',required=True)
    sub.add_parser('init'); a=sub.add_parser('add-user'); a.add_argument('id'); a.add_argument('name'); a.add_argument('role',choices=['student','organiser','admin'])
    s=sub.add_parser('serve'); s.add_argument('--host',default='0.0.0.0'); s.add_argument('--port',default=8000,type=int)
    args=p.parse_args(); init()
    if args.command=='add-user': add_user(args.id,args.name,args.role,getpass.getpass('New password (10+ characters): ')); print('User created.')
    elif args.command=='serve': print(f'ProxyProof API on {args.host}:{args.port}',flush=True); ThreadingHTTPServer((args.host,args.port),Handler).serve_forever()
    else: print('Database ready.')
