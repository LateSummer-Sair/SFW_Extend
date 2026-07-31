/* ============================================================
   AiAgent — Cyberpunk Interactive Scripts
   Features:
     - Particle canvas with neon glow
     - Mouse cursor trail (neon cyber lines)
     - Glitch text effect (random triggers)
     - Scroll reveal with blur fade
     - Nav highlight + shadow
     - Copy code feedback
     - Stats counter animation
     - Parallax on hero
   ============================================================ */

(function () {
    'use strict';

    // ==================== Mouse Trail Canvas ====================
    const trailCanvas = document.createElement('canvas');
    trailCanvas.id = 'mouse-trail-canvas';
    document.body.appendChild(trailCanvas);
    const tCtx = trailCanvas.getContext('2d');

    let tWidth, tHeight;
    const trailPoints = [];
    const MAX_TRAIL = 40;

    function resizeTrail() {
        tWidth = trailCanvas.width = window.innerWidth;
        tHeight = trailCanvas.height = window.innerHeight;
    }
    resizeTrail();

    let mouseTrailX = -100, mouseTrailY = -100;
    let targetTrailX = -100, targetTrailY = -100;

    document.addEventListener('mousemove', function (e) {
        targetTrailX = e.clientX;
        targetTrailY = e.clientY;
        // Spawn trail points
        trailPoints.push({
            x: e.clientX,
            y: e.clientY,
            life: 1.0,
            size: Math.random() * 3 + 1.5
        });
        if (trailPoints.length > MAX_TRAIL) {
            trailPoints.splice(0, trailPoints.length - MAX_TRAIL);
        }
    });

    document.addEventListener('mouseleave', function () {
        targetTrailX = -100;
        targetTrailY = -100;
    });

    function drawTrail() {
        tCtx.clearRect(0, 0, tWidth, tHeight);

        // Smooth mouse follow
        mouseTrailX += (targetTrailX - mouseTrailX) * 0.15;
        mouseTrailY += (targetTrailY - mouseTrailY) * 0.15;

        // Neon cursor glow
        if (targetTrailX > 0) {
            const g = tCtx.createRadialGradient(mouseTrailX, mouseTrailY, 0, mouseTrailX, mouseTrailY, 50);
            g.addColorStop(0, 'rgba(0, 240, 255, 0.12)');
            g.addColorStop(0.5, 'rgba(0, 240, 255, 0.04)');
            g.addColorStop(1, 'rgba(0, 240, 255, 0)');
            tCtx.fillStyle = g;
            tCtx.beginPath();
            tCtx.arc(mouseTrailX, mouseTrailY, 50, 0, Math.PI * 2);
            tCtx.fill();

            // Crosshair
            tCtx.strokeStyle = 'rgba(0, 240, 255, 0.25)';
            tCtx.lineWidth = 1;
            tCtx.beginPath();
            tCtx.moveTo(mouseTrailX - 15, mouseTrailY);
            tCtx.lineTo(mouseTrailX + 15, mouseTrailY);
            tCtx.moveTo(mouseTrailX, mouseTrailY - 15);
            tCtx.lineTo(mouseTrailX, mouseTrailY + 15);
            tCtx.stroke();
        }

        // Trail particles
        for (let i = trailPoints.length - 1; i >= 0; i--) {
            const p = trailPoints[i];
            p.life -= 0.025;
            if (p.life <= 0) {
                trailPoints.splice(i, 1);
                continue;
            }
            const alpha = p.life * 0.5;
            tCtx.fillStyle = 'rgba(0, 240, 255, ' + alpha + ')';
            tCtx.shadowColor = 'rgba(0, 240, 255, ' + (alpha * 0.6) + ')';
            tCtx.shadowBlur = 6;
            tCtx.beginPath();
            tCtx.arc(p.x, p.y, p.size * p.life, 0, Math.PI * 2);
            tCtx.fill();
            tCtx.shadowBlur = 0;
        }

        requestAnimationFrame(drawTrail);
    }
    drawTrail();

    window.addEventListener('resize', resizeTrail);

    // ==================== Particle Canvas (Enhanced) ====================
    const canvas = document.getElementById('particles-canvas');
    if (canvas) {
        const ctx = canvas.getContext('2d');
        let particles = [];
        let pMouseX = -1000, pMouseY = -1000;

        function pResize() {
            canvas.width = window.innerWidth;
            canvas.height = window.innerHeight;
        }

        class Particle {
            constructor() {
                this.reset();
                this.y = Math.random() * canvas.height;
            }

            reset() {
                this.x = Math.random() * canvas.width;
                this.y = -20;
                this.size = Math.random() * 2.5 + 0.5;
                this.speedY = Math.random() * 0.6 + 0.2;
                this.speedX = (Math.random() - 0.5) * 0.35;
                this.opacity = Math.random() * 0.5 + 0.15;
                // Mix of cyan and purple hues
                const r = Math.random();
                this.hue = r < 0.35 ? 190 : r < 0.55 ? 270 : r < 0.7 ? 300 : 130;
            }

            update() {
                this.y += this.speedY;
                this.x += this.speedX;

                // Mouse repulsion
                const dx = this.x - pMouseX;
                const dy = this.y - pMouseY;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 140) {
                    const force = (1 - dist / 140) * 1.2;
                    this.x += (dx / dist) * force;
                    this.y += (dy / dist) * force;
                }

                if (this.y > canvas.height + 30) { this.reset(); this.y = -20; }
                if (this.x < -30) this.x = canvas.width + 30;
                if (this.x > canvas.width + 30) this.x = -30;
            }

            draw(ctx) {
                ctx.beginPath();
                ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
                const hsl = 'hsla(' + this.hue + ', 85%, 65%, ' + this.opacity + ')';
                ctx.fillStyle = hsl;
                // Neon glow on larger particles
                if (this.size > 1.5) {
                    ctx.shadowColor = 'hsla(' + this.hue + ', 90%, 55%, ' + (this.opacity * 0.6) + ')';
                    ctx.shadowBlur = 8;
                }
                ctx.fill();
                ctx.shadowBlur = 0;
            }
        }

        function initParticles(count) {
            particles = [];
            for (let i = 0; i < count; i++) {
                particles.push(new Particle());
            }
        }

        function drawConnections() {
            for (let i = 0; i < particles.length; i++) {
                for (let j = i + 1; j < particles.length; j++) {
                    const dx = particles[i].x - particles[j].x;
                    const dy = particles[i].y - particles[j].y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 120) {
                        ctx.beginPath();
                        ctx.moveTo(particles[i].x, particles[i].y);
                        ctx.lineTo(particles[j].x, particles[j].y);
                        const alpha = 0.05 * (1 - dist / 120);
                        ctx.strokeStyle = 'rgba(0, 240, 255, ' + alpha + ')';
                        ctx.lineWidth = 0.6;
                        ctx.stroke();
                    }
                }
            }
        }

        function pAnimate() {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            for (const p of particles) { p.update(); p.draw(ctx); }
            drawConnections();
            requestAnimationFrame(pAnimate);
        }

        let resizeTimeout;
        window.addEventListener('resize', function () {
            pResize();
            clearTimeout(resizeTimeout);
            resizeTimeout = setTimeout(function () {
                initParticles(Math.floor((canvas.width * canvas.height) / 13000));
            }, 200);
        });

        canvas.addEventListener('mousemove', function (e) { pMouseX = e.clientX; pMouseY = e.clientY; });
        canvas.addEventListener('mouseleave', function () { pMouseX = -1000; pMouseY = -1000; });

        // Touch support
        canvas.addEventListener('touchmove', function (e) {
            if (e.touches.length > 0) { pMouseX = e.touches[0].clientX; pMouseY = e.touches[0].clientY; }
        });
        canvas.addEventListener('touchend', function () { pMouseX = -1000; pMouseY = -1000; });

        pResize();
        initParticles(Math.floor((canvas.width * canvas.height) / 13000));
        pAnimate();
    }

    // ==================== Glitch Text Effect ====================
    function glitchText(el) {
        const orig = el.textContent;
        const chars = '!@#$%^&*()_+-=[]{}|;:,.<>?/~`0123456789';
        let iterations = 0;
        const maxIter = 8;
        const interval = setInterval(function () {
            el.textContent = orig.split('').map(function (c, i) {
                if (i < iterations) return orig[i];
                return chars[Math.floor(Math.random() * chars.length)];
            }).join('');
            iterations += 0.7;
            if (iterations >= orig.length) {
                el.textContent = orig;
                clearInterval(interval);
            }
        }, 40);
    }

    // Glitch random headings on load
    window.addEventListener('load', function () {
        setTimeout(function () {
            const headings = document.querySelectorAll('.section-header h2, .pattern-card h4');
            if (headings.length > 0) {
                const randomH = headings[Math.floor(Math.random() * headings.length)];
                if (randomH) glitchText(randomH);
            }
        }, 600);

        // Periodic random glitch
        setInterval(function () {
            if (Math.random() < 0.25) {
                const cards = document.querySelectorAll('.pattern-card h4, .card h3');
                if (cards.length > 0) {
                    const rc = cards[Math.floor(Math.random() * cards.length)];
                    if (rc && rc.closest('.pattern-card:hover, .card:hover') === null) {
                        glitchText(rc);
                    }
                }
            }
        }, 12000);
    });

    // ==================== Hero Parallax ====================
    const hero = document.querySelector('.hero');
    if (hero) {
        window.addEventListener('scroll', function () {
            const scrollY = window.scrollY;
            if (scrollY < hero.offsetHeight) {
                const rate = scrollY * 0.4;
                hero.style.setProperty('--parallax-y', rate + 'px');
                const content = hero.querySelector('.hero-content');
                if (content) {
                    content.style.transform = 'translateY(' + (rate * 0.3) + 'px)';
                }
            }
        }, { passive: true });
    }

    // ==================== Scroll Reveal ====================
    const revealElements = document.querySelectorAll('.reveal');

    function checkReveal() {
        const trigger = window.innerHeight * 0.9;
        for (const el of revealElements) {
            const top = el.getBoundingClientRect().top;
            if (top < trigger) {
                el.classList.add('visible');
            }
        }
    }

    // ==================== Sidebar Navigation ====================
    const sidebarNavLinks = document.querySelectorAll('.sidebar-nav a');
    const sidebarSections = [];
    sidebarNavLinks.forEach(function (link) {
        const href = link.getAttribute('href');
        if (href && href.startsWith('#')) {
            const target = document.querySelector(href);
            if (target) sidebarSections.push({ link: link, target: target });
        }
    });

    function highlightNav() {
        const scrollY = window.scrollY + 140;
        let current = sidebarSections[0];
        for (const s of sidebarSections) {
            if (s.target.offsetTop <= scrollY) current = s;
        }
        sidebarNavLinks.forEach(function (l) { l.classList.remove('active'); });
        if (current) current.link.classList.add('active');
    }

    // ==================== Sidebar Toggle (Mobile) ====================
    const sidebar = document.getElementById('sidebar');
    const sidebarToggle = document.getElementById('sidebar-toggle');
    if (sidebar && sidebarToggle) {
        sidebarToggle.addEventListener('click', function () {
            sidebar.classList.toggle('open');
            sidebarToggle.classList.toggle('open');
        });

        // Close sidebar when clicking a link (mobile)
        document.querySelectorAll('.sidebar-nav a').forEach(function (link) {
            link.addEventListener('click', function () {
                if (window.innerWidth <= 768) {
                    sidebar.classList.remove('open');
                    sidebarToggle.classList.remove('open');
                }
            });
        });

        // Close sidebar when clicking outside
        document.addEventListener('click', function (e) {
            if (window.innerWidth <= 768 &&
                sidebar.classList.contains('open') &&
                !sidebar.contains(e.target) &&
                !sidebarToggle.contains(e.target)) {
                sidebar.classList.remove('open');
                sidebarToggle.classList.remove('open');
            }
        });
    }

    // ==================== Back to Top ====================
    const backTop = document.querySelector('.back-to-top');
    function checkBackTop() {
        backTop.classList.toggle('show', window.scrollY > 600);
    }
    backTop.addEventListener('click', function () {
        window.scrollTo({ top: 0, behavior: 'smooth' });
    });

    // ==================== Combined Scroll ====================
    window.addEventListener('scroll', function () {
        checkReveal();
        highlightNav();
        checkBackTop();
    }, { passive: true });

    checkReveal();
    highlightNav();
    checkBackTop();

    // ==================== Copy Code ====================
    document.querySelectorAll('.copy-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            const pre = this.closest('.code-block').querySelector('pre');
            const text = pre.textContent;
            navigator.clipboard.writeText(text).then(function () {
                const orig = btn.textContent;
                btn.textContent = '✓ COPIED';
                btn.style.color = '#39ff14';
                btn.style.borderColor = '#39ff14';
                btn.style.boxShadow = '0 0 10px rgba(57, 255, 20, 0.3)';
                setTimeout(function () {
                    btn.textContent = orig;
                    btn.style.color = '';
                    btn.style.borderColor = '';
                    btn.style.boxShadow = '';
                }, 2000);
            }).catch(function () {
                btn.textContent = 'FAILED';
                btn.style.color = '#ff2d6b';
                setTimeout(function () { btn.textContent = 'Copy'; btn.style.color = ''; }, 1500);
            });
        });
    });

    // ==================== Stats Counter Animation ====================
    const statNums = document.querySelectorAll('.hero-stat .num');
    let statsAnimated = false;

    function animateStats() {
        if (statsAnimated) return;
        const h = document.querySelector('.hero');
        if (!h) return;
        const rect = h.getBoundingClientRect();
        if (rect.bottom < 0) return;

        statsAnimated = true;
        statNums.forEach(function (el) {
            const target = parseInt(el.getAttribute('data-target'), 10);
            const duration = 1800;
            const start = performance.now();

            function tick(now) {
                const elapsed = now - start;
                const progress = Math.min(elapsed / duration, 1);
                const eased = 1 - Math.pow(1 - progress, 4);
                const current = Math.round(eased * target);
                el.textContent = current;
                if (progress < 1) {
                    requestAnimationFrame(tick);
                } else {
                    el.textContent = target;
                }
            }
            requestAnimationFrame(tick);
        });
    }

    window.addEventListener('scroll', animateStats, { passive: true });
    window.addEventListener('load', animateStats);

    // ==================== Cyber Card Hover Glow ====================
    document.querySelectorAll('.card, .pattern-card, .feature-list li').forEach(function (el) {
        el.addEventListener('mousemove', function (e) {
            const rect = el.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            el.style.setProperty('--mx', x + 'px');
            el.style.setProperty('--my', y + 'px');
        });
    });

    // ==================== Initial Glitch - Hero Title ====================
    window.addEventListener('load', function () {
        const heroTitle = document.querySelector('.hero h1 .gradient-text');
        if (heroTitle) {
            heroTitle.classList.add('glitch');
            heroTitle.setAttribute('data-text', heroTitle.textContent);
            // Remove glitch after initial effect
            setTimeout(function () {
                heroTitle.classList.remove('glitch');
            }, 2500);
        }
    });

})();
