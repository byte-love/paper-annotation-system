"""TLS proxy"""
import socket, ssl, threading
L = ('127.0.0.1', 14330)
T = ('127.0.0.1', 1433)
def fwd(a,b):
    try:
        while True:
            d = a.recv(8192)
            if not d: break
            b.sendall(d)
    except: pass
    finally:
        try: a.close()
        except: pass
        try: b.close()
        except: pass
def h(c):
    try:
        r = socket.socket(); r.settimeout(10); r.connect(T)
        x = ssl.SSLContext(ssl.PROTOCOL_TLSv1)
        x.set_ciphers('DEFAULT:@SECLEVEL=0')
        x.check_hostname = False; x.verify_mode = ssl.CERT_NONE
        s = x.wrap_socket(r)
        t1 = threading.Thread(target=fwd, args=(c,s), daemon=True)
        t2 = threading.Thread(target=fwd, args=(s,c), daemon=True)
        t1.start(); t2.start(); t1.join(); t2.join()
    except Exception as e: print(f'err: {e}')
    finally:
        try: c.close()
        except: pass
print(f'Proxy: {L[0]}:{L[1]} -> {T[0]}:{T[1]}')
v = socket.socket(); v.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
v.bind(L); v.listen(10)
while True:
    c, a = v.accept()
    threading.Thread(target=h, args=(c,), daemon=True).start()
