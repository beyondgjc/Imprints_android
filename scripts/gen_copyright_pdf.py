"""
生成软著登记用的「文档鉴别材料」PDF。

版权局要求（一般交存方式）：
  · 源程序前连续 30 页 + 后连续 30 页
  · 每页不少于 50 行（末页可不足）
  · 页眉标注软件名称、版本号、页码（与登记申请表一致）
  · PDF 格式

页数分配：前 30 页取代码开头，后 30 页取结尾。每页 50 行。
文件顺序：按包内相对路径字典序，保证「连续」且可复现。
"""
import os
import sys

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.pdfgen import canvas

# ---------- 参数 ----------
PROJECT = sys.argv[1] if len(sys.argv) > 1 else \
    r'C:\Users\beyondguo\Documents\Agents\Imprints\Imprints_android'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'Imprints_源程序.pdf'
SOFTWARE_NAME = '印迹'
VERSION = 'V1.3'
COPYRIGHT_OWNER = '郭继超'

PKG = '/com/beyondguo/imprints/'
LINES_PER_PAGE = 50
PAGES_EACH_SIDE = 30
# 软著材料惯例：去掉注释与空行，只保留有效代码；但保留文件分节标题以体现连续性
EXCLUDE_DIRS = {'build', '.git', '.gradle', '.idea', '.kotlin', 'captures'}


def collect():
    """按路径字典序收集主代码文件（排除测试）。"""
    files = []
    for root, dirs, fs in os.walk(PROJECT):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        for fn in fs:
            if not fn.endswith(('.kt', '.java')):
                continue
            p = os.path.join(root, fn).replace(os.sep, '/')
            if '/test/' in p or '/androidTest/' in p:
                continue
            files.append(p)
    files.sort(key=lambda x: x.split(PKG)[-1])
    return files


def build_lines():
    """把代码拼成带分节标题的行序列。"""
    lines = []
    for p in collect():
        rel = p.split(PKG)[-1] if PKG in p else os.path.basename(p)
        lines.append(f'// ===== 文件：{rel} =====')
        try:
            src = open(p, encoding='utf-8').read().rstrip('\n').split('\n')
        except (UnicodeDecodeError, OSError):
            continue
        for ln in src:
            t = ln.rstrip()
            if not t.strip():
                continue                      # 空行不留白
            if t.strip().startswith('//'):
                continue                      # 纯注释行不进材料
            lines.append(t)
    return lines


def make_pdf(lines, out):
    total_needed = PAGES_EACH_SIDE * 2
    if len(lines) < PAGES_EACH_SIDE * LINES_PER_PAGE * 2:
        print(f'⚠️ 代码仅 {len(lines)} 行，不足 {total_needed} 页所需')

    head = lines[:PAGES_EACH_SIDE * LINES_PER_PAGE]
    tail = lines[-PAGES_EACH_SIDE * LINES_PER_PAGE:] if len(lines) > PAGES_EACH_SIDE * LINES_PER_PAGE else []
    pages = [head[i * LINES_PER_PAGE:(i + 1) * LINES_PER_PAGE] for i in range(PAGES_EACH_SIDE)]
    if tail:
        pages += [tail[i * LINES_PER_PAGE:(i + 1) * LINES_PER_PAGE]
                  for i in range(PAGES_EACH_SIDE)]

    pdfmetrics.registerFont(UnicodeCIDFont('STSong-Light'))
    c = canvas.Canvas(out, pagesize=A4)
    w, h = A4
    left, right = 20 * mm, 15 * mm
    top, bottom = 22 * mm, 18 * mm
    body_size, body_leading = 8.2, 11.4

    n = len(pages)
    for i, page in enumerate(pages, 1):
        # 页眉：软件全称 + 版本 + 页码（与申请表一致）
        c.setFont('STSong-Light', 10.5)
        c.drawString(left, h - 14 * mm, f'{SOFTWARE_NAME}（{VERSION}）源程序')
        c.setFont('STSong-Light', 10)
        # 页码标在右上角（官方要求：右上角标注连续页号）
        c.drawRightString(w - right, h - 10* mm, str(i))

        # 页脚：著作权人
        c.setFont('STSong-Light', 8.5)
        c.drawString(left, 11 * mm, f'著作权人：{COPYRIGHT_OWNER}')
        c.drawRightString(w - right, 11 * mm, 'com.beyondguo.imprints')

        # 分隔线
        c.setLineWidth(0.4)
        c.line(left, h - 16 * mm, w - right, h - 16 * mm)

        # 正文
        c.setFont('STSong-Light', body_size)
        y = h - top
        for ln in page:
            c.drawString(left, y, ln[:110])
            y -= body_leading
        c.showPage()

    c.save()
    return n, len(lines)


if __name__ == '__main__':
    ls = build_lines()
    pages, total = make_pdf(ls, OUT)
    print(f'✅ 已生成 {OUT}')
    print(f'   总行数 {total} → {pages} 页（{LINES_PER_PAGE} 行/页）')
    print(f'   前 {PAGES_EACH_SIDE} 页 + 后 {PAGES_EACH_SIDE} 页')