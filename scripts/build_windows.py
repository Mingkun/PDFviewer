"""Build the Windows viewer.html from the Android assets + inject the desktop bridge."""
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'android/app/src/main/assets'
DST = ROOT / 'windows-app'

POLYFILL = """
<script>
window.AppBridge = {
  appVersion: function () { return '1.0.0 (windows)'; },
  checkUpdate: function () {
    fetch('https://5130599.best/Translator/downloads/pdfviewer-windows-version.json').then(function (r) { return r.json(); }).then(function (d) {
      if (d && !d.error) d.hasUpdate = (d.versionName || '') !== '1.0.0';
      if (window.onUpdateInfo) window.onUpdateInfo(d);
    }).catch(function () { if (window.onUpdateInfo) window.onUpdateInfo({ error: 'network' }); });
  },
  downloadUpdate: function (url) {
    try { if (window.electronOpen) window.electronOpen(url); } catch (e) {}
    if (window.onUpdateDownload) window.onUpdateDownload('done');
  },
  ttsSpeak: function (text, id) {
    try {
      var u = new SpeechSynthesisUtterance(text);
      u.lang = /[\\u4e00-\\u9fff]/.test(text) ? 'zh-CN' : 'en-US';
      u.rate = 0.9;
      u.onend = function () { if (window.onTtsDone) window.onTtsDone(); };
      u.onerror = function () { if (window.onTtsDone) window.onTtsDone(); };
      speechSynthesis.cancel();
      speechSynthesis.speak(u);
    } catch (e) { if (window.onTtsDone) window.onTtsDone(); }
  },
  ttsStop: function () { try { speechSynthesis.cancel(); } catch (e) {} }
};
window.addEventListener('wheel', function (e) {
  if (!e.ctrlKey) return;
  e.preventDefault();
  try {
    scale = Math.max(0.5, Math.min(4, scale + (e.deltaY < 0 ? 0.2 : -0.2)));
    if (pdf) render();
  } catch (err) {}
}, { passive: false });
window.addEventListener('keydown', function (e) {
  try {
    if (e.key === 'ArrowRight' || e.key === 'PageDown') { if (pdf && pageNum < pdf.numPages) { pageNum += 1; render(); } }
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp') { if (pdf && pageNum > 1) { pageNum -= 1; render(); } }
  } catch (err) {}
});
</script>
"""

def main():
    # copy pdf.js assets
    pdfjs_src = SRC / 'pdfjs'
    pdfjs_dst = DST / 'pdfjs'
    if pdfjs_dst.exists():
        shutil.rmtree(pdfjs_dst)
    shutil.copytree(pdfjs_src, pdfjs_dst)
    # build viewer.html with injected bridge
    html = (SRC / 'viewer.html').read_text(encoding='utf-8')
    anchor = '<script src="pdfjs/pdf.min.js"></script>'
    assert anchor in html, 'anchor missing'
    html = html.replace(anchor, anchor + POLYFILL, 1)
    # desktop: title
    html = html.replace('<title>PDF 阅读器</title>', '<title>PDF 阅读器</title>')
    (DST / 'viewer.html').write_text(html, encoding='utf-8')
    bad = [i for i, l in enumerate(html.split('\n'), 1) if '***' in l]
    print('viewer.html written,', len(html), 'chars, corruption:', bad if bad else 'none')
    print('pdfjs copied:', len(list(pdfjs_dst.iterdir())), 'files')

if __name__ == '__main__':
    main()
