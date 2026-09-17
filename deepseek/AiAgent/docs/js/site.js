/* ==========================================================================
   AiAgent V4 文档站 —— 前端交互（纯手写，无第三方库、无网络请求）
   功能：导航高亮 / 移动端菜单 / 阅读进度 / 滚动揭示 / 本页目录(滚动同步) /
        回到顶部 / 代码块复制 / 极简语法高亮 / 数据流画布 / 标题锚点
   所有增强都是"可选"的：JS 不跑，页面依然可读可导航。
   ========================================================================== */
(function () {
  'use strict';

  var doc = document;
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------------- 1. 导航：当前页高亮 ---------------- */
  function markNav() {
    var here = (location.pathname.split('/').pop() || 'index.html').toLowerCase();
    if (here === '') here = 'index.html';
    var links = doc.querySelectorAll('.nav a');
    for (var i = 0; i < links.length; i++) {
      var href = (links[i].getAttribute('href') || '').toLowerCase();
      if (href === here) {
        links[i].classList.add('active');
        links[i].setAttribute('aria-current', 'page');
      }
    }
  }

  /* ---------------- 2. 移动端菜单 ---------------- */
  function initBurger() {
    var btn = doc.querySelector('.burger');
    var nav = doc.querySelector('.nav');
    if (!btn || !nav) return;
    btn.addEventListener('click', function () {
      var on = nav.classList.toggle('open');
      btn.setAttribute('aria-expanded', on ? 'true' : 'false');
    });
    nav.addEventListener('click', function (e) {
      if (e.target && e.target.tagName === 'A') nav.classList.remove('open');
    });
  }

  /* ---------------- 3. 阅读进度 + 回到顶部 ---------------- */
  function initScrollUI() {
    var bar = doc.getElementById('progress');
    var top = doc.getElementById('to-top');
    function onScroll() {
      var h = doc.documentElement;
      var max = h.scrollHeight - h.clientHeight;
      var y = h.scrollTop || doc.body.scrollTop;
      if (bar) bar.style.width = (max > 0 ? (y / max) * 100 : 0) + '%';
      if (top) top.classList.toggle('show', y > 480);
    }
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    if (top) {
      top.addEventListener('click', function () {
        window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
      });
    }
    onScroll();
  }

  /* ---------------- 4. 滚动揭示 ---------------- */
  function initReveal() {
    var els = doc.querySelectorAll('.reveal');
    if (!els.length) return;
    if (reduce || !('IntersectionObserver' in window)) {
      for (var i = 0; i < els.length; i++) els[i].classList.add('in');
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add('in');
          io.unobserve(en.target);
        }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });
    for (var j = 0; j < els.length; j++) io.observe(els[j]);
  }

  /* ---------------- 5. 本页目录：自动生成 + 滚动同步 ---------------- */
  function slug(s, used) {
    var t = (s || '').trim()
      .replace(/[`*_~\[\]()（）「」【】：:，,。.、\/\\|]/g, '')
      .replace(/\s+/g, '-')
      .toLowerCase();
    if (!t) t = 'sec';
    t = t.slice(0, 60);
    var base = t, n = 2;
    while (used[t]) { t = base + '-' + n; n++; }
    used[t] = 1;
    return t;
  }

  function initToc() {
    var list = doc.getElementById('toc-list');
    var body = doc.querySelector('.doc-body');
    if (!list || !body) return;

    var used = {};
    // 先把页面里已有的 id 占位，避免自动生成的标题 id 与静态 id 撞车
    var existing = doc.querySelectorAll('[id]');
    for (var ei = 0; ei < existing.length; ei++) used[existing[ei].id] = 1;
    // 只收「章节级」标题：直接挂在 .chapter 下的 h2 / h3，
    // 面板与卡片里的 h3.sec 不进目录（否则目录会被说明性小标题塞满）
    var nodes = body.querySelectorAll('.chapter > .chapter-head > h2, .chapter > h3.sec');
    var heads = [];
    var seen = {};
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (seen[el.textContent]) continue;
      seen[el.textContent] = 1;
      heads.push(el);
    }

    heads.forEach(function (h) {
      var chapter = h.closest ? h.closest('.chapter') : null;
      if (!h.id) h.id = slug(h.textContent, used);
      var li = doc.createElement('li');
      var a = doc.createElement('a');
      a.href = '#' + h.id;
      a.textContent = h.textContent.replace(/^▍/, '').trim();
      if (h.tagName === 'H3') a.className = 'lv3';
      a.setAttribute('data-target', h.id);
      li.appendChild(a);
      list.appendChild(li);

      // 标题锚点：点击复制深链接
      var anchor = doc.createElement('a');
      anchor.className = 'h-anchor';
      anchor.href = '#' + h.id;
      anchor.title = '复制本小节链接';
      anchor.textContent = '#';
      anchor.addEventListener('click', function (e) {
        e.stopPropagation();
        var url = location.href.split('#')[0] + '#' + h.id;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(url).catch(function () {});
          anchor.textContent = '✓';
          setTimeout(function () { anchor.textContent = '#'; }, 1200);
        }
      });
      h.appendChild(anchor);
      void chapter;
    });

    var links = list.querySelectorAll('a');
    if (!links.length) {
      var rail = doc.querySelector('.rail');
      if (rail) rail.style.display = 'none';
      return;
    }

    if (!('IntersectionObserver' in window)) return;
    var current = null;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var id = en.target.id;
        if (id === current) return;
        current = id;
        for (var k = 0; k < links.length; k++) {
          links[k].classList.toggle('active', links[k].getAttribute('data-target') === id);
        }
      });
    }, { rootMargin: '-16% 0px -70% 0px', threshold: 0 });
    heads.forEach(function (h) { io.observe(h); });
  }

  /* ---------------- 6. 代码块：语言标签 + 复制 + 高亮 ---------------- */
  var KEYWORDS = {
    java: 'abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null',
    json: 'true|false|null',
    yaml: 'true|false|null|yes|no|on|off',
    bash: 'if|then|else|fi|for|do|done|while|case|esac|function|return|export|local|set|echo|cd|exit',
    powershell: 'if|else|elseif|foreach|for|while|function|param|return|try|catch|finally|throw|Set-Location|New-Item|Remove-Item|Get-ChildItem|ForEach-Object|Write-Host|Test-Path|Select-Object|Where-Object'
  };

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // 单遍扫描，避免"先替换后又被二次匹配"的经典坑
  function highlight(code, lang) {
    var L = (lang || '').toLowerCase();
    var kw = KEYWORDS[L] || KEYWORDS.java;
    var kwRe = new RegExp('^(?:' + kw + ')$');
    var out = '';
    var i = 0, n = code.length;

    function emit(cls, text) {
      out += cls ? '<span class="' + cls + '">' + esc(text) + '</span>' : esc(text);
    }

    while (i < n) {
      var c = code[i];

      // 行注释
      if ((L === 'java' || L === 'js' || L === 'javascript' || L === '') && c === '/' && code[i + 1] === '/') {
        var e1 = code.indexOf('\n', i); if (e1 < 0) e1 = n;
        emit('tk-cmt', code.slice(i, e1)); i = e1; continue;
      }
      if ((L === 'bash' || L === 'powershell' || L === 'ps1') && c === '#') {
        var e2 = code.indexOf('\n', i); if (e2 < 0) e2 = n;
        emit('tk-cmt', code.slice(i, e2)); i = e2; continue;
      }
      if (L === 'yaml' || L === 'properties' || L === 'ini') {
        if (c === '#') {
          var e3 = code.indexOf('\n', i); if (e3 < 0) e3 = n;
          emit('tk-cmt', code.slice(i, e3)); i = e3; continue;
        }
      }
      if (c === '/' && code[i + 1] === '*') {
        var e4 = code.indexOf('*/', i + 2); e4 = e4 < 0 ? n : e4 + 2;
        emit('tk-cmt', code.slice(i, e4)); i = e4; continue;
      }
      // 字符串
      if (c === '"' || c === "'" || c === '`') {
        var j = i + 1;
        while (j < n) {
          if (code[j] === '\\') { j += 2; continue; }
          if (code[j] === c) { j++; break; }
          if (code[j] === '\n' && c !== '`') break;
          j++;
        }
        emit('tk-str', code.slice(i, j)); i = j; continue;
      }
      // 数字
      if (c >= '0' && c <= '9' && !/[A-Za-z_$]/.test(code[i - 1] || '')) {
        var k = i;
        while (k < n && /[0-9a-fA-FxX._]/.test(code[k])) k++;
        emit('tk-num', code.slice(i, k)); i = k; continue;
      }
      // 标识符 / 关键字
      if (/[A-Za-z_$\u4e00-\u9fa5]/.test(c)) {
        var m = i;
        while (m < n && /[A-Za-z0-9_$\u4e00-\u9fa5.]/.test(code[m])) m++;
        var word = code.slice(i, m);
        var bare = word.indexOf('.') >= 0 ? word.slice(word.lastIndexOf('.') + 1) : word;
        if (kwRe.test(word)) emit('tk-kw', word);
        else if (/^[A-Z][A-Za-z0-9_]*$/.test(bare)) emit('tk-typ', word);
        else if (L === 'json' && /"(?:[^"]*)"\s*:/.test(code.slice(i - 1, m + 2))) emit('tk-key2', word);
        else out += esc(word);
        i = m; continue;
      }
      // JSON/YAML 的键名
      if ((L === 'json' || L === 'yaml') && c === ':' && (code[i + 1] === ' ' || code[i + 1] === '\n')) {
        emit('tk-punc', ':'); i++; continue;
      }
      out += esc(c);
      i++;
    }
    return out;
  }

  function initCode() {
    var blocks = doc.querySelectorAll('.code');
    for (var i = 0; i < blocks.length; i++) {
      (function (box) {
        var codeEl = box.querySelector('pre > code');
        if (!codeEl) return;
        var lang = box.getAttribute('data-lang') || (box.querySelector('.lang') || {}).textContent || '';
        var raw = codeEl.textContent;
        codeEl.innerHTML = highlight(raw, lang);

        var btn = box.querySelector('.copy');
        if (!btn) return;
        btn.addEventListener('click', function () {
          var text = raw;
          function done() {
            btn.textContent = '已复制';
            btn.classList.add('done');
            setTimeout(function () { btn.textContent = '复制'; btn.classList.remove('done'); }, 1400);
          }
          if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done).catch(fallback);
          } else fallback();
          function fallback() {
            var ta = doc.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            doc.body.appendChild(ta);
            ta.select();
            try { doc.execCommand('copy'); done(); } catch (e) { btn.textContent = '复制失败'; }
            doc.body.removeChild(ta);
          }
        });
      })(blocks[i]);
    }
  }

  /* ---------------- 7. 数据流画布（纯本地，轻量） ---------------- */
  function initCanvas() {
    var cv = doc.getElementById('bg-canvas');
    if (!cv || reduce) return;
    var ctx = cv.getContext('2d');
    if (!ctx) return;

    var W = 0, H = 0, DPR = Math.min(window.devicePixelRatio || 1, 2);
    var cols = [];
    var COLS = 26;
    var running = true;

    function size() {
      W = cv.clientWidth; H = cv.clientHeight;
      cv.width = Math.floor(W * DPR);
      cv.height = Math.floor(H * DPR);
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
      cols = [];
      for (var i = 0; i < COLS; i++) {
        cols.push({
          x: (i + 0.5) * (W / COLS),
          y: Math.random() * H,
          v: 0.25 + Math.random() * 0.75,
          len: 40 + Math.random() * 150,
          a: 0.05 + Math.random() * 0.2
        });
      }
    }

    function frame() {
      if (!running) return;
      ctx.clearRect(0, 0, W, H);
      for (var i = 0; i < cols.length; i++) {
        var c = cols[i];
        c.y += c.v;
        if (c.y - c.len > H) { c.y = -10; c.len = 40 + Math.random() * 150; c.a = 0.05 + Math.random() * 0.2; }
        var g = ctx.createLinearGradient(c.x, c.y - c.len, c.x, c.y);
        g.addColorStop(0, 'rgba(61,220,151,0)');
        g.addColorStop(1, 'rgba(61,220,151,' + c.a.toFixed(3) + ')');
        ctx.strokeStyle = g;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(c.x, c.y - c.len);
        ctx.lineTo(c.x, c.y);
        ctx.stroke();
        ctx.fillStyle = 'rgba(255,180,84,' + (c.a * 1.5).toFixed(3) + ')';
        ctx.fillRect(c.x - 1, c.y - 1, 2, 2);
      }
      requestAnimationFrame(frame);
    }

    size();
    window.addEventListener('resize', size);
    doc.addEventListener('visibilitychange', function () {
      if (doc.hidden) { running = false; }
      else if (!running) { running = true; requestAnimationFrame(frame); }
    });
    requestAnimationFrame(frame);
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    markNav();
    initBurger();
    initScrollUI();
    initToc();
    initCode();
    initReveal();
    initCanvas();
  }

  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
