"""Заглушка бэкенда: нужна только чтобы проверить, что сервер и HTTPS работают."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            code, body = 200, {"status": "ok", "service": "backend-stub"}
        else:
            code, body = 404, {"error": "not implemented yet"}
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print("backend stub listening on :8000")
    HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
