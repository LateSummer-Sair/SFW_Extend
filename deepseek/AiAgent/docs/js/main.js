/* ============================================================
   AiAgent 技术文档 v5.0 — 交互脚本
   粒子连线 · 极光 · 多级菜单 · 滚动进度 · 数字滚动 · reveal
   流程图顺序点亮 · 3D 倾斜 · 打字机 · 代码复制
   ============================================================ */

(function () {
  "use strict";

  /* ============ 全局导航数据（多级缩进菜单） ============ */
  const NAV = [
    { type: "link", href: "index.html", icon: "🏠", label: "首页" },

    { type: "group", icon: "🏗", label: "系统架构", children: [
      { type: "link", href: "architecture.html", label: "架构总览" },
      { type: "link", href: "architecture.html#dataflow", label: "数据流全景" },
      { type: "link", href: "architecture.html#fc-loop", label: "Function Calling 循环" },
      { type: "link", href: "architecture.html#multi-agent", label: "多智能体协作" },
      { type: "link", href: "architecture.html#context", label: "上下文注入链" },
      { type: "link", href: "architecture.html#structure", label: "目录结构" },
    ]},

    { type: "group", icon: "🔐", label: "权限与通道", children: [
      { type: "link", href: "permissions.html", label: "三层权限模型" },
      { type: "link", href: "permissions.html#matrix", label: "工具权限矩阵" },
      { type: "link", href: "permissions.html#gate", label: "越权拦截流程" },
      { type: "link", href: "permissions.html#code-safety", label: "高危代码拦截" },
    ]},

    { type: "group", icon: "🧠", label: "技能系统", children: [
      { type: "link", href: "skills.html", label: "技能总览" },
      { type: "link", href: "skills.html#builtin", label: "内置技能库" },
      { type: "link", href: "skills.html#thirdparty", label: "三方技能" },
      { type: "link", href: "skills.html#package", label: "技能包" },
      { type: "link", href: "skills.html#distill", label: "技能蒸馏" },
      { type: "link", href: "skills.html#route", label: "路径路由" },
    ]},

    { type: "group", icon: "💾", label: "记忆系统", children: [
      { type: "link", href: "memory.html", label: "记忆总览" },
      { type: "link", href: "memory.html#layers", label: "六库存储" },
      { type: "link", href: "memory.html#impression", label: "人格印象" },
      { type: "link", href: "memory.html#correct", label: "纠正记录" },
      { type: "link", href: "memory.html#lifecycle", label: "记忆生命周期" },
    ]},

    { type: "group", icon: "🤖", label: "QQ 机器人", children: [
      { type: "link", href: "architecture.html#onebot", label: "OneBot 架构" },
      { type: "link", href: "permissions.html#qq", label: "QQ 通道权限" },
      { type: "link", href: "commands.html#onebot-cmds", label: "QQ 命令" },
      { type: "link", href: "memory.html#qq-memory", label: "统一 QQ 记忆" },
    ]},

    { type: "group", icon: "📋", label: "命令参考", children: [
      { type: "link", href: "commands.html", label: "命令总览" },
      { type: "link", href: "commands.html#core-cmds", label: "核心命令" },
      { type: "link", href: "commands.html#onebot-cmds", label: "QQ 命令" },
      { type: "link", href: "commands.html#skills-cmds", label: "技能命令" },
      { type: "group", icon: "🔧", label: "工具清单", children: [
        { type: "link", href: "commands.html#tools-local", label: "本地全量工具" },
        { type: "link", href: "commands.html#tools-execq", label: "execq 受限工具" },
        { type: "link", href: "commands.html#tools-execs", label: "execs 全权限工具" },
      ]},
      { type: "link", href: "commands.html#deploy", label: "部署指南" },
    ]},

    { type: "group", icon: "📦", label: "类结构", children: [
      { type: "link", href: "classes.html", label: "类结构总览" },
      { type: "link", href: "classes.html#core", label: "core 核心模块" },
      { type: "link", href: "classes.html#onebot", label: "onebot QQ 模块" },
      { type: "link", href: "classes.html#model", label: "model 模型" },
      { type: "link", href: "classes.html#util", label: "工具 / UI / 命令层" },
    ]},

    { type: "link", href: "patterns.html", icon: "🎨", label: "设计模式" },

    { type: "link", href: "changelog.html", icon: "📜", label: "更新日志" },
  ];

  /* ============ 渲染多级导航 ============ */
  function renderNav() {
    const sidebar = document.getElementById("sidebar");
    if (!sidebar) return;
    const here = location.pathname.split("/").pop() || "index.html";

    const logo = document.createElement("div");
    logo.className = "sidebar-logo";
    logo.innerHTML = '<div class="icon">⚡</div><span class="logo-text">AiAgent<span class="ver">v3.9</span></span>';
    sidebar.appendChild(logo);

    const nav = document.createElement("nav");
    nav.className = "sidebar-nav";

    function buildLink(item) {
      const a = document.createElement("a");
      a.href = item.href;
      a.className = "nav-link";
      if (item.icon) {
        a.innerHTML = '<span class="nicon">' + item.icon + "</span>" + item.label;
      } else {
        a.textContent = item.label;
      }
      const base = item.href.split("#")[0];
      if (base === here) a.classList.add("active");
      return a;
    }

    function buildGroup(group, depth) {
      const g = document.createElement("div");
      g.className = "nav-group" + (depth > 0 ? " nested" : "");
      g.style.setProperty("--depth", depth);

      const title = document.createElement("button");
      title.className = "nav-title";
      title.innerHTML =
        '<span class="gico">' + (group.icon || "•") + '</span><span class="glabel">' + group.label +
        '</span><span class="arrow">▾</span>';
      g.appendChild(title);

      const sub = document.createElement("div");
      sub.className = "nav-sub";
      const inner = document.createElement("div");
      inner.className = "nav-sub-inner";

      let hasActive = false;
      group.children.forEach(function (child) {
        if (child.type === "group") {
          const cg = buildGroup(child, depth + 1);
          inner.appendChild(cg);
          if (cg.dataset.hasActive === "1") hasActive = true;
        } else {
          inner.appendChild(buildLink(child));
          if (child.href.split("#")[0] === here) hasActive = true;
        }
      });

      sub.appendChild(inner);
      g.appendChild(sub);

      if (hasActive) {
        g.classList.add("open");
        g.dataset.hasActive = "1";
      }

      title.addEventListener("click", function () {
        const wasOpen = g.classList.contains("open");
        // 手风琴：只关闭同级其他组
        const siblings = g.parentElement.children;
        Array.prototype.forEach.call(siblings, function (s) {
          if (s !== g && s.classList && s.classList.contains("nav-group")) {
            s.classList.remove("open");
          }
        });
        g.classList.toggle("open", !wasOpen);
      });

      return g;
    }

    NAV.forEach(function (item) {
      if (item.type === "link") nav.appendChild(buildLink(item));
      else nav.appendChild(buildGroup(item, 0));
    });

    sidebar.appendChild(nav);
  }

  /* ============ 粒子连线背景 ============ */
  function initParticles() {
    const canvas = document.getElementById("bg-canvas");
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    let w, h, particles = [];
    const DPR = Math.min(window.devicePixelRatio || 1, 2);
    const COUNT = Math.min(100, Math.floor((window.innerWidth * window.innerHeight) / 17000));
    const mouse = { x: -9999, y: -9999 };

    function resize() {
      w = window.innerWidth; h = window.innerHeight;
      canvas.width = w * DPR; canvas.height = h * DPR;
      canvas.style.width = w + "px"; canvas.style.height = h + "px";
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    }
    resize();

    function rand(a, b) { return a + Math.random() * (b - a); }
    const colors = ["0,229,255", "168,85,247", "255,45,120", "16,185,129"];
    for (let i = 0; i < COUNT; i++) {
      particles.push({
        x: rand(0, w), y: rand(0, h),
        vx: rand(-0.35, 0.35), vy: rand(-0.35, 0.35),
        r: rand(1, 2.8),
        c: colors[Math.floor(Math.random() * colors.length)]
      });
    }

    function draw() {
      ctx.clearRect(0, 0, w, h);
      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];
        p.x += p.vx; p.y += p.vy;
        if (p.x < 0 || p.x > w) p.vx *= -1;
        if (p.y < 0 || p.y > h) p.vy *= -1;

        const dxm = mouse.x - p.x, dym = mouse.y - p.y;
        const dm = Math.hypot(dxm, dym);
        if (dm < 150) { p.x += dxm * 0.002; p.y += dym * 0.002; }

        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fillStyle = "rgba(" + p.c + ",0.72)";
        ctx.fill();

        for (let j = i + 1; j < particles.length; j++) {
          const q = particles[j];
          const dx = p.x - q.x, dy = p.y - q.y;
          const d = Math.hypot(dx, dy);
          if (d < 135) {
            ctx.beginPath();
            ctx.moveTo(p.x, p.y); ctx.lineTo(q.x, q.y);
            ctx.strokeStyle = "rgba(" + p.c + "," + (0.17 * (1 - d / 135)) + ")";
            ctx.lineWidth = 0.6;
            ctx.stroke();
          }
        }
        if (dm < 170) {
          ctx.beginPath();
          ctx.moveTo(p.x, p.y); ctx.lineTo(mouse.x, mouse.y);
          ctx.strokeStyle = "rgba(0,229,255," + (0.26 * (1 - dm / 170)) + ")";
          ctx.lineWidth = 0.7;
          ctx.stroke();
        }
      }
      requestAnimationFrame(draw);
    }
    draw();

    window.addEventListener("resize", resize);
    window.addEventListener("mousemove", function (e) { mouse.x = e.clientX; mouse.y = e.clientY; });
    window.addEventListener("mouseleave", function () { mouse.x = -9999; mouse.y = -9999; });
  }

  /* ============ 滚动进度条 ============ */
  function initProgress() {
    const bar = document.querySelector(".progress-bar");
    if (!bar) return;
    function update() {
      const h = document.documentElement;
      const sc = h.scrollTop / (h.scrollHeight - h.clientHeight);
      bar.style.width = (sc * 100) + "%";
    }
    window.addEventListener("scroll", update, { passive: true });
    update();
  }

  /* ============ 数字滚动动画 ============ */
  function initCounters() {
    const nums = document.querySelectorAll("[data-target]");
    if (!nums.length) return;
    const io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        const el = en.target;
        io.unobserve(el);
        const target = parseInt(el.getAttribute("data-target"), 10);
        const dur = 1700; const t0 = performance.now();
        function tick(t) {
          const p = Math.min((t - t0) / dur, 1);
          const eased = 1 - Math.pow(1 - p, 3);
          el.textContent = Math.floor(target * eased).toLocaleString();
          if (p < 1) requestAnimationFrame(tick);
          else el.textContent = target.toLocaleString();
        }
        requestAnimationFrame(tick);
      });
    }, { threshold: 0.4 });
    nums.forEach(function (n) { io.observe(n); });
  }

  /* ============ 滚动显现 ============ */
  function initReveal() {
    const els = document.querySelectorAll(".reveal");
    if (!els.length) return;
    const io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add("visible"); io.unobserve(en.target); }
      });
    }, { threshold: 0.12 });
    els.forEach(function (el) { io.observe(el); });
  }

  /* ============ 流程图顺序点亮 ============ */
  function initFlow() {
    const flows = document.querySelectorAll(".flow");
    flows.forEach(function (flow) {
      const items = flow.querySelectorAll(".flow-node, .flow-edge");
      if (!items.length) return;
      const io = new IntersectionObserver(function (entries) {
        entries.forEach(function (en) {
          if (!en.isIntersecting) return;
          io.unobserve(en.target);
          let delay = 0;
          items.forEach(function (it, idx) {
            setTimeout(function () { it.classList.add("on"); }, 150 * idx);
            delay = 150 * idx;
          });
          // 补充分支内的节点
          flow.querySelectorAll(".flow-branch .flow-node").forEach(function (it, idx) {
            setTimeout(function () { it.classList.add("on"); }, delay + 150 * (idx + 1));
          });
        });
      }, { threshold: 0.25 });
      io.observe(flow);
    });
  }

  /* ============ 3D 倾斜卡 ============ */
  function initTilt() {
    if (window.matchMedia("(max-width: 960px)").matches) return;
    document.querySelectorAll(".tilt").forEach(function (card) {
      card.addEventListener("mousemove", function (e) {
        const r = card.getBoundingClientRect();
        const x = (e.clientX - r.left) / r.width - 0.5;
        const y = (e.clientY - r.top) / r.height - 0.5;
        card.style.transform = "perspective(800px) rotateY(" + (x * 8) + "deg) rotateX(" + (-y * 8) + "deg) translateY(-4px)";
      });
      card.addEventListener("mouseleave", function () {
        card.style.transform = "perspective(800px) rotateY(0) rotateX(0) translateY(0)";
      });
    });
  }

  /* ============ 打字机效果 ============ */
  function initTyping() {
    document.querySelectorAll("[data-type]").forEach(function (el) {
      const text = el.getAttribute("data-type");
      const speed = parseInt(el.getAttribute("data-speed") || "40", 10);
      let i = 0;
      el.textContent = "";
      el.insertAdjacentHTML("beforeend", '<span class="type-cursor"></span>');
      const cursor = el.querySelector(".type-cursor");
      function type() {
        if (i < text.length) {
          cursor.insertAdjacentText("beforebegin", text.charAt(i));
          i++;
          setTimeout(type, speed);
        } else {
          cursor.remove();
        }
      }
      setTimeout(type, 400);
    });
  }

  /* ============ 代码复制 ============ */
  function initCopy() {
    document.querySelectorAll(".copy-btn").forEach(function (btn) {
      btn.addEventListener("click", function () {
        const pre = btn.closest("pre") || btn.parentElement.querySelector("pre");
        if (!pre) return;
        const text = pre.innerText;
        const done = function () {
          const old = btn.textContent;
          btn.textContent = "已复制 ✓";
          setTimeout(function () { btn.textContent = old; }, 1600);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done).catch(function () { fallbackCopy(text); done(); });
        } else { fallbackCopy(text); done(); }
      });
    });
    function fallbackCopy(text) {
      const ta = document.createElement("textarea");
      ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta); ta.select();
      try { document.execCommand("copy"); } catch (e) {}
      document.body.removeChild(ta);
    }
  }

  /* ============ 返回顶部 ============ */
  function initBackTop() {
    const btn = document.querySelector(".back-to-top");
    if (!btn) return;
    window.addEventListener("scroll", function () {
      btn.classList.toggle("show", window.scrollY > 500);
    }, { passive: true });
    btn.addEventListener("click", function () {
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
  }

  /* ============ 锚点滚动偏移 ============ */
  function initAnchor() {
    document.querySelectorAll('a[href^="#"]').forEach(function (a) {
      a.addEventListener("click", function (e) {
        const id = a.getAttribute("href").slice(1);
        if (!id) return;
        const el = document.getElementById(id);
        if (el) {
          e.preventDefault();
          const y = el.getBoundingClientRect().top + window.scrollY - 20;
          window.scrollTo({ top: y, behavior: "smooth" });
          history.replaceState(null, "", "#" + id);
        }
      });
    });
  }

  /* ============ 移动端侧边栏 ============ */
  function initMobileSidebar() {
    const toggle = document.getElementById("sidebar-toggle");
    const sidebar = document.getElementById("sidebar");
    if (toggle && sidebar) {
      toggle.addEventListener("click", function () {
        sidebar.classList.toggle("open");
      });
      document.addEventListener("click", function (e) {
        if (sidebar.classList.contains("open") && !sidebar.contains(e.target) && !toggle.contains(e.target)) {
          sidebar.classList.remove("open");
        }
      });
    }
  }

  /* ============ 启动 ============ */
  document.addEventListener("DOMContentLoaded", function () {
    renderNav();
    initParticles();
    initProgress();
    initCounters();
    initReveal();
    initFlow();
    initTilt();
    initTyping();
    initCopy();
    initBackTop();
    initAnchor();
    initMobileSidebar();
  });
})();
