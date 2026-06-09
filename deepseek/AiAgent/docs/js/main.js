/* ============================================================
   AiAgent — Technical Documentation Scripts
   Features: Particle canvas, scroll reveal, nav highlight,
             copy code, back-to-top, smooth interaction.
   ============================================================ */

(function () {
    'use strict';

    // ==================== Particle Canvas ====================
    const canvas = document.getElementById('particles-canvas');
    if (canvas) {
        const ctx = canvas.getContext('2d');
        let particles = [];
        let animId;
        let mouseX = -1000, mouseY = -1000;

        function resize() {
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
                this.y = -10;
                this.size = Math.random() * 2 + 0.5;
                this.speedY = Math.random() * 0.5 + 0.2;
                this.speedX = (Math.random() - 0.5) * 0.3;
                this.opacity = Math.random() * 0.6 + 0.1;
                this.hue = Math.random() < 0.5 ? 190 : 270; // cyan or purple
            }

            update() {
                this.y += this.speedY;
                this.x += this.speedX;

                // Mouse interaction
                const dx = this.x - mouseX;
                const dy = this.y - mouseY;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 120) {
                    this.x += (dx / dist) * 0.8;
                    this.y += (dy / dist) * 0.8;
                }

                if (this.y > canvas.height + 10) {
                    this.reset();
                    this.y = -10;
                }
                if (this.x < -10) this.x = canvas.width + 10;
                if (this.x > canvas.width + 10) this.x = -10;
            }

            draw(ctx) {
                ctx.beginPath();
                ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
                ctx.fillStyle = `hsla(${this.hue}, 80%, 70%, ${this.opacity})`;
                ctx.fill();
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
                    if (dist < 100) {
                        ctx.beginPath();
                        ctx.moveTo(particles[i].x, particles[i].y);
                        ctx.lineTo(particles[j].x, particles[j].y);
                        ctx.strokeStyle = `rgba(34, 211, 238, ${0.04 * (1 - dist / 100)})`;
                        ctx.lineWidth = 0.5;
                        ctx.stroke();
                    }
                }
            }
        }

        function animate() {
            ctx.clearRect(0, 0, canvas.width, canvas.height);

            for (const p of particles) {
                p.update();
                p.draw(ctx);
            }
            drawConnections();

            animId = requestAnimationFrame(animate);
        }

        window.addEventListener('resize', () => {
            resize();
            initParticles(Math.floor((canvas.width * canvas.height) / 15000));
        });

        canvas.addEventListener('mousemove', function (e) {
            mouseX = e.clientX;
            mouseY = e.clientY;
        });

        canvas.addEventListener('mouseleave', function () {
            mouseX = -1000;
            mouseY = -1000;
        });

        resize();
        initParticles(Math.floor((canvas.width * canvas.height) / 15000));
        animate();
    }

    // ==================== Scroll Reveal ====================
    const revealElements = document.querySelectorAll('.reveal');

    function checkReveal() {
        const trigger = window.innerHeight * 0.88;
        for (const el of revealElements) {
            const top = el.getBoundingClientRect().top;
            if (top < trigger) {
                el.classList.add('visible');
            }
        }
    }

    // ==================== Navigation Highlight ====================
    const navLinks = document.querySelectorAll('.nav-links a');
    const sections = [];
    navLinks.forEach(function (link) {
        const href = link.getAttribute('href');
        if (href && href.startsWith('#')) {
            const target = document.querySelector(href);
            if (target) sections.push({ link: link, target: target });
        }
    });

    function highlightNav() {
        const scrollY = window.scrollY + 120;
        let current = sections[0];
        for (const s of sections) {
            if (s.target.offsetTop <= scrollY) {
                current = s;
            }
        }
        navLinks.forEach(function (l) { l.classList.remove('active'); });
        if (current) current.link.classList.add('active');
    }

    // ==================== Top Nav Shadow ====================
    const topNav = document.querySelector('.top-nav');
    function checkNavShadow() {
        if (window.scrollY > 40) {
            topNav.classList.add('scrolled');
        } else {
            topNav.classList.remove('scrolled');
        }
    }

    // ==================== Back to Top ====================
    const backTop = document.querySelector('.back-to-top');
    function checkBackTop() {
        if (window.scrollY > 600) {
            backTop.classList.add('show');
        } else {
            backTop.classList.remove('show');
        }
    }

    backTop.addEventListener('click', function () {
        window.scrollTo({ top: 0, behavior: 'smooth' });
    });

    // ==================== Combined Scroll ====================
    window.addEventListener('scroll', function () {
        checkReveal();
        highlightNav();
        checkNavShadow();
        checkBackTop();
    }, { passive: true });

    // Initial check
    checkReveal();
    highlightNav();
    checkNavShadow();
    checkBackTop();

    // ==================== Copy Code ====================
    document.querySelectorAll('.copy-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            const pre = this.closest('.code-block').querySelector('pre');
            const text = pre.textContent;
            navigator.clipboard.writeText(text).then(function () {
                const orig = btn.textContent;
                btn.textContent = 'Copied!';
                btn.style.color = '#4ade80';
                btn.style.borderColor = '#4ade80';
                setTimeout(function () {
                    btn.textContent = orig;
                    btn.style.color = '';
                    btn.style.borderColor = '';
                }, 2000);
            }).catch(function () {
                btn.textContent = 'Failed';
                setTimeout(function () { btn.textContent = 'Copy'; }, 1500);
            });
        });
    });

    // ==================== Stats Counter Animation ====================
    const statNums = document.querySelectorAll('.hero-stat .num');
    let statsAnimated = false;

    function animateStats() {
        if (statsAnimated) return;
        const hero = document.querySelector('.hero');
        if (!hero) return;
        const rect = hero.getBoundingClientRect();
        if (rect.bottom < 0) return;

        statsAnimated = true;
        statNums.forEach(function (el) {
            const target = parseInt(el.getAttribute('data-target'), 10);
            const duration = 1500;
            const start = performance.now();

            function tick(now) {
                const elapsed = now - start;
                const progress = Math.min(elapsed / duration, 1);
                // ease-out
                const eased = 1 - Math.pow(1 - progress, 3);
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

    window.addEventListener('scroll', animateStats, { passive: true, once: false });
    // Also try on load
    window.addEventListener('load', animateStats);

})();
