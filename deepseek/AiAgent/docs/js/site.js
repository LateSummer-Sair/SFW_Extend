/* ==========================================================================
   AiAgent V4 文档站 —— 前端交互（纯手写，无第三方库、无外部请求，离线可用）
   功能：导航形态与高亮 / 阅读进度 / 滚动揭示 / 本页目录(滚动同步) /
        回到顶部 / 代码块复制 / 极简语法高亮 / 数据流画布 / 标题锚点 / 逐字浮现
   所有增强都是"可选"的：JS 不跑，页面依然可读、可导航、可滚动。
   ========================================================================== */
(function () {
  'use strict';

  var doc = document;
  var raf = window.requestAnimationFrame
    ? window.requestAnimationFrame.bind(window)
    : function (fn) { return setTimeout(fn, 16); };

  function mqReduce() {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }
  var reduce = mqReduce();

  function $(sel, root) { return (root || doc).querySelector(sel); }
  function $$(sel, root) { return (root || doc).querySelectorAll(sel); }

  /* ---------------- 1. 导航：当前页高亮 ---------------- */
  function markNav() {
    var here = (location.pathname.split('/').pop() || 'index.html').toLowerCase();
    if (here === '') here = 'index.html';
    var links = $$('.nav a');
    for (var i = 0; i < links.length; i++) {
      var href = (links[i].getAttribute('href') || '').toLowerCase();
      if (href === here) {
        links[i].classList.add('active');
        links[i].setAttribute('aria-current', 'page');
      }
    }
  }

  /* ---------------- 2. 移动端导航形态 ----------------
     主方案（CSS）：≤980px 时 .nav 是「始终可见的横向滚动条」，不依赖任何按钮。
     这里的 JS 只做三件事：
       a) 探测 .nav 是否真被 CSS 收成了 0 高度（例如样式没更新/被缓存）；
          若是，则启用抽屉兜底：自己 createElement('button') 插进顶栏，
          带 aria 属性、可键盘操作（Enter/Space 原生、Esc 关闭）；
       b) 横向条模式下，把可能存在的旧 .burger 彻底隐藏，避免出现"点了没反应"的死按钮；
       c) 把当前页链接滚进可视区，并给 .nav 挂 can-left / can-right 提示可滑方向。
     --------------------------------------------------------- */
  function initMenu() {
    var nav = $('.nav');
    if (!nav) return 'none';
    if (!nav.id) nav.id = 'site-nav';

    var btn = $('.burger');

    // 只有当 max-height 被算成 0 时才算"被藏起来"
    var collapsed = false;
    try {
      var cs = window.getComputedStyle(nav);
      var mh = cs.maxHeight;
      collapsed = (mh && mh !== 'none' && parseFloat(mh) === 0);
    } catch (e) { collapsed = false; }

    if (!collapsed) {
      // 横向滚动条模式：清掉旧的抽屉痕迹
      doc.documentElement.classList.remove('js-drawer');
      if (btn) {
        btn.hidden = true;
        btn.setAttribute('aria-hidden', 'true');
        btn.setAttribute('tabindex', '-1');
      }
      nav.classList.remove('open');
      return 'strip';
    }

    // ---- 抽屉兜底路径（正常部署下不会走到） ----
    doc.documentElement.classList.add('js-drawer');
    if (!btn) {
      btn = doc.createElement('button');
      btn.type = 'button';
      btn.className = 'burger';
      btn.textContent = '☰';
      var bar = $('.topbar') || doc.body;
      var meta = $('.topbar-meta', bar);
      if (meta && meta.parentNode === bar) bar.insertBefore(btn, meta);
      else bar.appendChild(btn);
    } else {
      btn.hidden = false;
      btn.removeAttribute('aria-hidden');
      btn.removeAttribute('tabindex');
    }
    btn.setAttribute('aria-label', '展开导航菜单');
    btn.setAttribute('aria-controls', nav.id);
    btn.setAttribute('aria-expanded', 'false');

    function setOpen(on) {
      nav.classList.toggle('open', !!on);
      btn.setAttribute('aria-expanded', on ? 'true' : 'false');
      btn.setAttribute('aria-label', on ? '收起导航菜单' : '展开导航菜单');
    }
    btn.addEventListener('click', function () {
      setOpen(!nav.classList.contains('open'));
    });
    nav.addEventListener('click', function (e) {
      var t = e.target;
      if (t && t.tagName === 'A' && nav.classList.contains('open')) setOpen(false);
    });
    doc.addEventListener('keydown', function (e) {
      var k = e.key;
      if ((k === 'Escape' || k === 'Esc') && nav.classList.contains('open')) {
        setOpen(false);
        btn.focus();
      }
    });
    window.addEventListener('resize', function () { setOpen(false); });
    return 'drawer';
  }

  // 横向滚动条：标记左右是否还有内容（CSS 用它做边缘渐隐提示）
  function navEdges(nav) {
    var canLeft = nav.scrollLeft > 2;
    var canRight = nav.scrollLeft + nav.clientWidth < nav.scrollWidth - 2;
    nav.classList.toggle('can-left', canLeft);
    nav.classList.toggle('can-right', canRight);
  }

  // 把当前页那一项滚到横向条中间（用 rect 差值，绝不触发页面纵向滚动）
  function ensureActiveVisible(nav) {
    var a = $('a.active', nav) || $('a', nav);
    if (!a || !nav.clientWidth) return;
    var nr = nav.getBoundingClientRect();
    var ar = a.getBoundingClientRect();
    if (ar.left >= nr.left && ar.right <= nr.right) return;
    var target = nav.scrollLeft + (ar.left - nr.left) - (nr.width - ar.width) / 2;
    if (target < 0) target = 0;
    if (nav.scrollTo) nav.scrollTo({ left: target, behavior: reduce ? 'auto' : 'smooth' });
    else nav.scrollLeft = target;
  }

  function initNav() {
    var mode = initMenu();
    var nav = $('.nav');
    if (!nav) return;

    var ticking = false;
    function sync() { ticking = false; navEdges(nav); }
    function onScroll() { if (!ticking) { ticking = true; raf(sync); } }

    nav.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    window.addEventListener('orientationchange', onScroll);

    navEdges(nav);
    ensureActiveVisible(nav);
    // 字体/布局稳定后再校准一次
    window.addEventListener('load', function () { navEdges(nav); ensureActiveVisible(nav); });
    setTimeout(function () { navEdges(nav); ensureActiveVisible(nav); }, 80);
    void mode;
  }

  /* ---------------- 3. 阅读进度 + 回到顶部 ---------------- */
  function initScrollUI() {
    var bar = doc.getElementById('progress');
    var top = doc.getElementById('to-top');
    var ticking = false;

    function apply() {
      ticking = false;
      var h = doc.documentElement;
      var max = h.scrollHeight - h.clientHeight;
      var y = h.scrollTop || doc.body.scrollTop || 0;
      if (bar) bar.style.width = (max > 0 ? (y / max) * 100 : 0) + '%';
      if (top) top.classList.toggle('show', y > 480);
    }
    function onScroll() { if (!ticking) { ticking = true; raf(apply); } }

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    if (top) {
      if (!top.getAttribute('aria-label')) top.setAttribute('aria-label', '回到页面顶部');
      top.addEventListener('click', function () {
        window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
      });
    }
    apply();
  }

  /* ---------------- 4. 滚动揭示 ----------------
     4a) HTML 里已经写了 .reveal 的元素（原有机制，保持不变）
     4b) 额外给常见区块自动补挂 .reveal：不改 HTML，不要求任何新 class。
         只挑"首屏以外"的元素，避免进场时闪一下。
     4c) 没有 IntersectionObserver 或用户要求减少动效时，全部直接可见。
     --------------------------------------------------------- */
  function initReveal() {
    var els = $$('.reveal');
    var i;

    if (reduce || !('IntersectionObserver' in window)) {
      for (i = 0; i < els.length; i++) els[i].classList.add('in');
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

    for (i = 0; i < els.length; i++) io.observe(els[i]);

    var sel = '.chapter-head, .panel, .tile, .note, .code, .keyrow, .release, .tl-item, .kpi, .tw';
    var nodes = $$(sel);
    var vh = window.innerHeight || doc.documentElement.clientHeight || 800;
    for (i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.classList.contains('reveal')) continue;
      if (el.getBoundingClientRect().top < vh * 0.92) continue;   // 首屏内不动
      el.classList.add('reveal');
      if (!el.classList.contains('d1') && !el.classList.contains('d2') && !el.classList.contains('d3')) {
        el.style.transitionDelay = ((i % 3) * 60) + 'ms';
      }
      io.observe(el);
    }
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
    var body = $('.doc-body');
    if (!list || !body) return;

    var used = {};
    // 先把页面里已有的 id 占位，避免自动生成的标题 id 与静态 id 撞车
    var existing = $$('[id]');
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
      anchor.setAttribute('aria-label', '复制本小节链接');
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
      var rail = $('.rail');
      if (rail) rail.style.display = 'none';
      return;
    }

    if (reduce || !('IntersectionObserver' in window)) return;
    var current = null;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var id = en.target.id;
        if (id === current) return;
        current = id;
        for (var k = 0; k < links.length; k++) {
          var on = links[k].getAttribute('data-target') === id;
          links[k].classList.toggle('active', on);
          if (on) links[k].setAttribute('aria-current', 'location');
          else links[k].removeAttribute('aria-current');
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
    sh: 'if|then|else|fi|for|do|done|while|case|esac|function|return|export|local|set|echo|cd|exit',
    powershell: 'if|else|elseif|foreach|for|while|function|param|return|try|catch|finally|throw|Set-Location|New-Item|Remove-Item|Get-ChildItem|ForEach-Object|Write-Host|Test-Path|Select-Object|Where-Object',
    python: 'and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|return|True|False|try|while|with|yield|self|print|len|range|str|int|dict|list',
    sql: 'select|from|where|insert|into|values|update|set|delete|create|table|index|view|drop|alter|add|primary|key|foreign|references|join|left|right|inner|outer|on|group|by|order|having|limit|offset|and|or|not|null|as|distinct|count|sum|avg|min|max|case|when|then|else|end|begin|commit|rollback|union|all|exists|in|like|between|is',
    bat: 'if|else|for|in|do|goto|call|set|echo|exit|rem|not|exist|defined|start|cd|copy|move|del|mkdir|pause|shift|errorlevel'
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

    var hashLang = (L === 'bash' || L === 'sh' || L === 'powershell' || L === 'ps1' ||
                    L === 'python' || L === 'py' || L === 'yaml' || L === 'yml' ||
                    L === 'properties' || L === 'ini' || L === 'conf');
    var dashLang = (L === 'sql');

    while (i < n) {
      var c = code[i];

      // 行注释
      if ((L === 'java' || L === 'js' || L === 'javascript' || L === '') && c === '/' && code[i + 1] === '/') {
        var e1 = code.indexOf('\n', i); if (e1 < 0) e1 = n;
        emit('tk-cmt', code.slice(i, e1)); i = e1; continue;
      }
      if (dashLang && c === '-' && code[i + 1] === '-') {
        var es = code.indexOf('\n', i); if (es < 0) es = n;
        emit('tk-cmt', code.slice(i, es)); i = es; continue;
      }
      if (hashLang && c === '#') {
        var e2 = code.indexOf('\n', i); if (e2 < 0) e2 = n;
        emit('tk-cmt', code.slice(i, e2)); i = e2; continue;
      }
      if ((L === 'bat' || L === 'cmd') && c === ':' && code[i + 1] === ':') {
        var e7 = code.indexOf('\n', i); if (e7 < 0) e7 = n;
        emit('tk-cmt', code.slice(i, e7)); i = e7; continue;
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
        var isKw = kwRe.test(word) || (dashLang && kwRe.test(word.toLowerCase()));
        if (isKw) emit('tk-kw', word);
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
    var blocks = $$('.code');
    for (var i = 0; i < blocks.length; i++) {
      (function (box) {
        var codeEl = box.querySelector('pre > code');
        if (!codeEl) return;
        var lang = box.getAttribute('data-lang') || (box.querySelector('.lang') || {}).textContent || '';
        var raw = codeEl.textContent;
        codeEl.innerHTML = highlight(raw, lang);

        var btn = box.querySelector('.copy');
        if (!btn) return;
        if (!btn.getAttribute('aria-label')) btn.setAttribute('aria-label', '复制代码');
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
    var running = true;
    var COLS = window.innerWidth < 720 ? 14 : 26;

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
        g.addColorStop(0, 'rgba(153,255,204,0)');
        g.addColorStop(1, 'rgba(153,255,204,' + c.a.toFixed(3) + ')');
        ctx.strokeStyle = g;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(c.x, c.y - c.len);
        ctx.lineTo(c.x, c.y);
        ctx.stroke();
        ctx.fillStyle = 'rgba(204,204,255,' + (c.a * 1.5).toFixed(3) + ')';
        ctx.fillRect(c.x - 1, c.y - 1, 2, 2);
      }
      raf(frame);
    }

    size();
    window.addEventListener('resize', size);
    doc.addEventListener('visibilitychange', function () {
      if (doc.hidden) { running = false; }
      else if (!running) { running = true; raf(frame); }
    });

    // 用户运行中切到「减少动效」时立刻停掉
    if (window.matchMedia) {
      var m = window.matchMedia('(prefers-reduced-motion: reduce)');
      var handler = function () {
        reduce = mqReduce();
        if (reduce) {
          running = false;
          cv.style.display = 'none';
        }
      };
      if (m.addEventListener) m.addEventListener('change', handler);
      else if (m.addListener) m.addListener(handler);
    }

    raf(frame);
  }

  /* ---------------- 8. 逐字浮现（不改 HTML，用 JS 拆纯文本节点） ---------------- */
  function splitChars(root, step, skipSel) {
    if (!root || !doc.createTreeWalker) return false;
    var walker = doc.createTreeWalker(root, window.NodeFilter ? NodeFilter.SHOW_TEXT : 4, null);
    var texts = [];
    while (walker.nextNode()) texts.push(walker.currentNode);

    var idx = 0, count = 0;
    for (var t = 0; t < texts.length; t++) {
      var tn = texts[t];
      var raw = tn.nodeValue;
      if (!raw || !/\S/.test(raw)) continue;
      // 跳过渐变裁字（.grad）等不能拆的区域：webkit-text-fill-color 会继承，
      // 拆成子元素后裁字背景丢失，字会变透明。
      if (skipSel) {
        var p = tn.parentNode, skip = false;
        while (p && p !== root) {
          if (p.nodeType === 1 && p.matches && p.matches(skipSel)) { skip = true; break; }
          p = p.parentNode;
        }
        if (skip) continue;
      }
      var frag = doc.createDocumentFragment();
      for (var i = 0; i < raw.length; i++) {
        var ch = raw.charAt(i);
        if (/\s/.test(ch)) { frag.appendChild(doc.createTextNode(ch)); idx++; continue; }
        var span = doc.createElement('span');
        span.className = 'ch';
        span.textContent = ch;
        // 内联样式优先级最高：防止站点里已有的后代规则（例如 .brand-text span{display:block}）
        // 把拆出来的字变成块级、一个字一行。
        span.style.display = 'inline-block';
        span.style.animationDelay = (idx * step) + 'ms';
        idx++; count++;
        frag.appendChild(span);
      }
      tn.parentNode.replaceChild(frag, tn);
    }
    if (count > 0) root.classList.add('ch-split');
    return count > 0;
  }

  function initCharSplit() {
    if (reduce) return;                       // 减少动效：完全不动 DOM
    // 只拆首屏大标题 .hero h1（块级、纯排版流），并跳过 .grad 渐变字（它交给 grad-flow）。
    // 不拆 .brand-text b：品牌是 display:flex 里的窄格，且 .brand-text span 有
    // display:block / font-size:10.5px 的后代规则，拆字后会被压成一列竖排。
    // 不拆 .eyebrow：它是 display:inline-flex，拆出的 span 会变成 flex item，
    // 空白文本节点被忽略且不换行，窄屏会挤成一行。
    splitChars($('.hero h1'), 30, '.grad');
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    markNav();
    initNav();
    initScrollUI();
    initToc();
    initCode();
    initReveal();
    initCanvas();
    initCharSplit();
  }

  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
