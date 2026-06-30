/* ============================================
   PeaceHaven 拾光录 - 交互逻辑
   ============================================ */

document.addEventListener('DOMContentLoaded', () => {

    // === Theme Toggle ===
    const body = document.body;
    const themeToggle = document.getElementById('themeToggle');
    const savedTheme = localStorage.getItem('ph-theme');
    if (savedTheme) body.setAttribute('data-theme', savedTheme);

    themeToggle.addEventListener('click', () => {
        const current = body.getAttribute('data-theme');
        const next = current === 'day' ? 'night' : 'day';
        body.setAttribute('data-theme', next);
        localStorage.setItem('ph-theme', next);
        // Reinit particles with new theme color
        initParticles();
    });

    // === Navbar Scroll Effect ===
    const navbar = document.getElementById('navbar');
    const backToTop = document.getElementById('backToTop');

    window.addEventListener('scroll', () => {
        const scrollY = window.scrollY;
        navbar.classList.toggle('scrolled', scrollY > 50);
        backToTop.classList.toggle('visible', scrollY > 500);
    });

    backToTop.addEventListener('click', () => {
        window.scrollTo({ top: 0, behavior: 'smooth' });
    });

    // === Mobile Menu ===
    const hamburger = document.getElementById('hamburger');
    const navLinks = document.getElementById('navLinks');

    hamburger.addEventListener('click', () => {
        hamburger.classList.toggle('active');
        navLinks.classList.toggle('open');
    });

    // Close menu on link click
    navLinks.querySelectorAll('.nav-link').forEach(link => {
        link.addEventListener('click', () => {
            hamburger.classList.remove('active');
            navLinks.classList.remove('open');
        });
    });

    // === Active Nav Link on Scroll ===
    const sections = document.querySelectorAll('.section, .hero');
    const navLinkEls = document.querySelectorAll('.nav-link');

    const observerNav = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const id = entry.target.id;
                navLinkEls.forEach(link => {
                    link.classList.toggle('active', link.getAttribute('href') === '#' + id);
                });
            }
        });
    }, { threshold: 0.3 });

    sections.forEach(sec => observerNav.observe(sec));

    // === Hero Carousel ===
    const slides = document.querySelectorAll('.carousel-slide');
    const prevBtn = document.getElementById('carouselPrev');
    const nextBtn = document.getElementById('carouselNext');
    let currentSlide = 0;
    let carouselInterval;

    function goToSlide(index) {
        slides[currentSlide].classList.remove('active');
        currentSlide = (index + slides.length) % slides.length;
        slides[currentSlide].classList.add('active');
    }

    function nextSlide() { goToSlide(currentSlide + 1); }
    function prevSlide() { goToSlide(currentSlide - 1); }

    function startCarousel() {
        carouselInterval = setInterval(nextSlide, 5000);
    }

    function resetCarousel() {
        clearInterval(carouselInterval);
        startCarousel();
    }

    prevBtn.addEventListener('click', () => { prevSlide(); resetCarousel(); });
    nextBtn.addEventListener('click', () => { nextSlide(); resetCarousel(); });

    startCarousel();

    // === Fade-in on Scroll ===
    const fadeEls = document.querySelectorAll('.fade-in');

    const observerFade = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('visible');
                observerFade.unobserve(entry.target);
            }
        });
    }, { threshold: 0.15, rootMargin: '0px 0px -40px 0px' });

    fadeEls.forEach(el => observerFade.observe(el));

    // === Counter Animation ===
    const statNumbers = document.querySelectorAll('.stat-number[data-target], .stat-number[data-start-date]');

    const observerCounter = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const el = entry.target;
                let target;
                if (el.dataset.startDate) {
                    // Calculate days from start date to now
                    const start = new Date(el.dataset.startDate);
                    const now = new Date();
                    target = Math.floor((now - start) / (1000 * 60 * 60 * 24));
                } else {
                    target = parseInt(el.dataset.target);
                }
                animateCounter(el, target);
                observerCounter.unobserve(el);
            }
        });
    }, { threshold: 0.5 });

    statNumbers.forEach(el => observerCounter.observe(el));

    function animateCounter(el, target) {
        let current = 0;
        const duration = 2000;
        const step = target / (duration / 16);

        function update() {
            current += step;
            if (current >= target) {
                el.textContent = target;
                return;
            }
            el.textContent = Math.floor(current);
            requestAnimationFrame(update);
        }
        requestAnimationFrame(update);
    }

    // === Team Card Tilt Effect ===
    const tiltCards = document.querySelectorAll('[data-tilt]');

    tiltCards.forEach(card => {
        card.addEventListener('mousemove', (e) => {
            const rect = card.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            const centerX = rect.width / 2;
            const centerY = rect.height / 2;
            const rotateX = (y - centerY) / centerY * -6;
            const rotateY = (x - centerX) / centerX * 6;

            card.style.transform = `translateY(-8px) perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg)`;

            // Move glow
            const glow = card.querySelector('.card-glow');
            if (glow) {
                glow.style.left = `${x - rect.width}px`;
                glow.style.top = `${y - rect.height}px`;
            }
        });

        card.addEventListener('mouseleave', () => {
            card.style.transform = 'translateY(0) perspective(1000px) rotateX(0) rotateY(0)';
        });
    });

    // === Particle System ===
    const canvas = document.getElementById('particleCanvas');
    const ctx = canvas.getContext('2d');
    let particles = [];
    let animFrameId;

    function resizeCanvas() {
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
    }

    function initParticles() {
        resizeCanvas();
        particles = [];
        const count = Math.min(Math.floor(window.innerWidth / 25), 50);
        const style = getComputedStyle(document.body);
        const color = style.getPropertyValue('--particle-color').trim() || 'rgba(232,131,58,0.4)';

        for (let i = 0; i < count; i++) {
            particles.push({
                x: Math.random() * canvas.width,
                y: Math.random() * canvas.height,
                radius: Math.random() * 2.5 + 0.5,
                vx: (Math.random() - 0.5) * 0.4,
                vy: (Math.random() - 0.5) * 0.3 - 0.1,
                color: color,
                opacity: Math.random() * 0.5 + 0.2
            });
        }
    }

    function drawParticles() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        particles.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;

            if (p.x < 0) p.x = canvas.width;
            if (p.x > canvas.width) p.x = 0;
            if (p.y < 0) p.y = canvas.height;
            if (p.y > canvas.height) p.y = 0;

            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            ctx.fillStyle = p.color;
            ctx.globalAlpha = p.opacity;
            ctx.fill();
        });

        // Draw connections
        ctx.globalAlpha = 1;
        for (let i = 0; i < particles.length; i++) {
            for (let j = i + 1; j < particles.length; j++) {
                const dx = particles[i].x - particles[j].x;
                const dy = particles[i].y - particles[j].y;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 120) {
                    ctx.beginPath();
                    ctx.moveTo(particles[i].x, particles[i].y);
                    ctx.lineTo(particles[j].x, particles[j].y);
                    const style = getComputedStyle(document.body);
                    const lineColor = style.getPropertyValue('--particle-color').trim() || 'rgba(232,131,58,0.2)';
                    ctx.strokeStyle = lineColor;
                    ctx.globalAlpha = (1 - dist / 120) * 0.3;
                    ctx.lineWidth = 0.5;
                    ctx.stroke();
                }
            }
        }
        ctx.globalAlpha = 1;

        animFrameId = requestAnimationFrame(drawParticles);
    }

    initParticles();
    drawParticles();

    window.addEventListener('resize', () => {
        resizeCanvas();
    });

    // === Smooth Scroll for Anchor Links ===
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            const target = document.querySelector(this.getAttribute('href'));
            if (target) {
                e.preventDefault();
                const offset = navbar.offsetHeight;
                const top = target.getBoundingClientRect().top + window.scrollY - offset;
                window.scrollTo({ top, behavior: 'smooth' });
            }
        });
    });

    // === Parallax on Hero (PC only) ===
    window.addEventListener('scroll', () => {
        if (window.innerWidth <= 768) return;
        const scrollY = window.scrollY;
        const heroContent = document.querySelector('.hero-content');
        if (heroContent && scrollY < window.innerHeight) {
            heroContent.style.transform = `translateY(${scrollY * 0.3}px)`;
            heroContent.style.opacity = 1 - scrollY / window.innerHeight;
        }
    });

    // === Scroll Progress Dots ===
    const scrollDots = document.querySelectorAll('.scroll-dot');
    const scrollTargets = ['hero', 'about', 'team', 'activities', 'benefits', 'footer'];
    const scrollSections = scrollTargets.map(id => document.getElementById(id)).filter(Boolean);

    // Click to navigate
    scrollDots.forEach(dot => {
        dot.addEventListener('click', () => {
            const targetId = dot.dataset.target;
            const targetEl = document.getElementById(targetId);
            if (targetEl) {
                const offset = navbar.offsetHeight;
                const top = targetEl.getBoundingClientRect().top + window.scrollY - offset;
                window.scrollTo({ top, behavior: 'smooth' });
            }
        });
    });

    // Update active dot on scroll
    function updateScrollDots() {
        const scrollY = window.scrollY;
        const windowH = window.innerHeight;
        const docH = document.documentElement.scrollHeight;
        let activeIndex = 0;

        // If at bottom of page, activate last dot
        if (scrollY + windowH >= docH - 50) {
            activeIndex = scrollSections.length - 1;
        } else {
            scrollSections.forEach((sec, i) => {
                const rect = sec.getBoundingClientRect();
                // Section is considered active when its top is above 40% of viewport
                if (rect.top <= windowH * 0.4) {
                    activeIndex = i;
                }
            });
        }

        scrollDots.forEach((dot, i) => {
            dot.classList.toggle('active', i === activeIndex);
        });
    }

    window.addEventListener('scroll', updateScrollDots);
    updateScrollDots();

});
