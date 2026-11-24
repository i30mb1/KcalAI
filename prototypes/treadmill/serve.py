"""Прототип дорожки на http://localhost:8787 — Web Bluetooth работает только на localhost или https.

Запуск: python prototypes/treadmill/serve.py
Страница написана без <html>/<head> (так её принимает и Artifact), обёртку даёт сервер.
"""
import http.server
import pathlib
import webbrowser

ROOT = pathlib.Path(__file__).resolve().parent
PORT = 8787
SHELL = (
    '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
    '<style>body{margin:0}[hidden]{display:none!important}</style></head><body>{page}</body></html>'
)


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      '.js': 'text/javascript; charset=utf-8'}

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        if self.path.split('?')[0] in ('/', '/index.html'):
            page = SHELL.replace('{page}', (ROOT / 'index.html').read_text('utf-8')).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(page)))
            self.end_headers()
            self.wfile.write(page)
            return
        super().do_GET()

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    url = f'http://localhost:{PORT}'
    print(f'Дорожка: {url}  (демо: {url}/#demo)')
    webbrowser.open(url)
    http.server.ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
