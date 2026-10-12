from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import socket,struct
from pathlib import Path
class Tracker(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/fixture.torrent':
            body=(Path(__file__).resolve().parents[2]/'android-tv/app/src/androidTest/assets/fixture.torrent').read_bytes()
            self.send_response(200);self.send_header('Content-Type','application/x-bittorrent');self.send_header('Content-Length',str(len(body)))
            self.end_headers();self.wfile.write(body);return
        if not self.path.startswith('/announce'):self.send_error(404);return
        peers=socket.inet_aton('10.0.2.2')+struct.pack('!H',18766)
        body=b'd8:completei1e10:incompletei0e8:intervali10e5:peers6:'+peers+b'e'
        self.send_response(200);self.send_header('Content-Type','text/plain');self.send_header('Content-Length',str(len(body)))
        self.end_headers();self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1',18765),Tracker).serve_forever()
