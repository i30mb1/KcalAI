"""Политика конфиденциальности Kcal AI — на сайт: docs/privacy-policy.md -> i30mb1.github.io/kcalai/policy/index.html.

Текст правится только в docs/privacy-policy.md; страница сайта собирается из него этим скриптом (заголовки, абзацы,
списки, **жирный**, ссылки — больше в политике ничего нет). Сайт — соседний репозиторий i30mb1.github.io:
после сборки там остаётся закоммитить и отправить (git push), GitHub Pages опубликует сам.

Запуск из корня KcalAI:  python tools/play/render_policy.py [путь к i30mb1.github.io]
Адрес страницы: https://i30mb1.github.io/kcalai/policy/ — его же открывает экран Health Connect (HealthPolicyActivity).
"""
import html
import pathlib
import re
import sys

SOURCE = pathlib.Path('docs/privacy-policy.md')
SITE = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '../i30mb1.github.io')
TARGET = SITE / 'kcalai' / 'policy' / 'index.html'

STYLE = """
  body { margin: 0; background: #1c1914; color: #f4efe4; font: 16px/1.6 system-ui, sans-serif; }
  main { max-width: 720px; margin: 0 auto; padding: 48px 20px; }
  h1 { font-size: 28px; margin: 48px 0 8px; line-height: 1.25; }
  h1:first-child { margin-top: 0; }
  h2 { font-size: 18px; margin: 32px 0 8px; }
  p, li { color: #d9d1c2; }
  a { color: #f4efe4; }
  code { font-size: 14px; }
  hr { border: 0; border-top: 1px solid #3a3328; margin: 48px 0; }
"""


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)
    text = re.sub(r'(?<!\*)\*([^*\s][^*]*)\*(?!\*)', r'<em>\1</em>', text)
    return re.sub(r'(https?://[^\s<)]+[^\s<).,;])', r'<a href="\1">\1</a>', text)


def render(md):
    out, para, items = [], [], []

    def flush():
        if para:
            out.append('<p>' + inline(' '.join(para)) + '</p>')
            para.clear()
        if items:
            out.append('<ul>' + ''.join('<li>' + inline(i) + '</li>' for i in items) + '</ul>')
            items.clear()

    for line in md.splitlines():
        s = line.strip()
        if not s:
            flush()
        elif s == '---':
            flush(); out.append('<hr>')
        elif s.startswith('## '):
            flush(); out.append('<h2>' + inline(s[3:]) + '</h2>')
        elif s.startswith('# '):
            flush(); out.append('<h1>' + inline(s[2:]) + '</h1>')
        elif s.startswith('- '):
            if para:
                flush()
            items.append(s[2:])
        elif items and line.startswith('  '):
            items[-1] += ' ' + s
        else:
            if items:
                flush()
            para.append(s)
    flush()
    return '\n  '.join(out)


def main():
    body = render(SOURCE.read_text(encoding='utf-8'))
    page = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kcal AI · Политика конфиденциальности</title>
<style>{STYLE}</style>
</head>
<body>
<main>
  {body}
</main>
</body>
</html>
"""
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(page, encoding='utf-8', newline='\n')
    print(f'{TARGET}: {len(page)} байт')


if __name__ == '__main__':
    main()
