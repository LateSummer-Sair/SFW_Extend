#!/usr/bin/env python3
"""AiAgent Docs - Feedback Server (zero-dependency)
Run: python feedback_server.py [port]

Endpoints:
  GET  /api/feedback  -> read  feedback.json (returns [] if missing)
  POST /api/feedback  -> write feedback.json (body = full JSON array)
"""

import json
import os
import socket
import sys
from http.server import HTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse

DOCS_DIR = os.path.dirname(os.path.abspath(__file__))
FB_FILE = os.path.join(DOCS_DIR, 'feedback.json')
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DOCS_DIR, **kwargs)

    def log_message(self, fmt, *args):
        if '/api/feedback' in str(args):
            print(f"  [{self.command}] /api/feedback  {args[1]} {args[2]}")

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.send_header('Cache-Control', 'no-cache,no-store,must-revalidate')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        if urlparse(self.path).path == '/api/feedback':
            self._get_feedback()
        else:
            super().do_GET()

    def do_POST(self):
        if urlparse(self.path).path == '/api/feedback':
            self._post_feedback()
        else:
            self.send_error(405)

    # --- API handlers ---

    def _get_feedback(self):
        data = []
        if os.path.exists(FB_FILE):
            try:
                with open(FB_FILE, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                if not isinstance(data, list):
                    data = []
            except (json.JSONDecodeError, IOError):
                data = []
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self._json_response(200, body)

    def _post_feedback(self):
        length = int(self.headers.get('Content-Length', 0))
        if length == 0:
            self.send_error(400)
            return
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode('utf-8'))
            if not isinstance(data, list):
                raise ValueError('not array')
        except Exception:
            self.send_error(400)
            return
        with open(FB_FILE, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        body = json.dumps({'ok': True, 'count': len(data)},
                          ensure_ascii=False).encode('utf-8')
        self._json_response(200, body)

    def _json_response(self, code, body):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    print(f'\n  AiAgent Feedback Server')
    print(f'  Static : http://localhost:{PORT}/')
    print(f'  API GET: http://localhost:{PORT}/api/feedback')
    print(f'  API POST:http://localhost:{PORT}/api/feedback')
    print(f'\n  Ctrl+C to stop\n')
    class DualStackServer(HTTPServer):
        address_family = socket.AF_INET6

    server = DualStackServer(('::', PORT), Handler)
    server.serve_forever()
