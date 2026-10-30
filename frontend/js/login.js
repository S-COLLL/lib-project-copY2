import { api, session, DEMO_MODE } from './api.js';
import { icon } from './icons.js';
import { BRAND_SVG, validateForm, formData, applyServerErrors, withLoading, toast, setTheme, currentTheme, qp } from './app.js';

const next = qp('next');
const goNext = () => {
  location.href = next && /^[\w-]+\.html/.test(next) ? next : 'dashboard.html';
};

if (session.token && !qp('expired') && !qp('loggedout')) goNext();

document.getElementById('brandMark').innerHTML = BRAND_SVG;
document.getElementById('yr').textContent = new Date().getFullYear();
document.getElementById('themeBtn').innerHTML = icon('sun', 'sun') + icon('moon', 'moon');
document.getElementById('themeBtn').addEventListener('click', (e) => setTheme(currentTheme() === 'dark' ? 'light' : 'dark', e.currentTarget));

// Input icons
const emailInput = document.getElementById('l-email');
emailInput.insertAdjacentHTML('beforebegin', icon('mail'));
const passInput = document.getElementById('l-pass');
passInput.insertAdjacentHTML('beforebegin', icon('lock'));
passInput.insertAdjacentHTML('afterend', `<button type="button" class="pw-toggle" aria-label="Show password">${icon('eye')}</button>`);
document.querySelector('.pw-toggle').addEventListener('click', () => {
  passInput.type = passInput.type === 'password' ? 'text' : 'password';
});

const feats = [
  ['mapPin', 'Exact shelf locations'],
  ['sparkles', 'AI library assistant'],
  ['qr', 'QR & barcode lookup'],
  ['rupee', 'Fines & payments in ₹'],
];
document.getElementById('heroFeats').innerHTML = feats.map(([i, t], k) => `<div class="hero-feat" style="animation-delay:${300 + k * 90}ms">${icon(i)}${t}</div>`).join('');
document.getElementById('shelfArt').innerHTML = [90, 120, 70, 140, 100, 60, 130, 110, 85, 150, 95, 75]
  .map((hgt, i) => `<span class="${i % 5 === 3 ? 'g' : ''}" style="height:${hgt}px;animation-delay:${i * 60}ms"></span>`)
  .join('');

const alertBox = document.getElementById('formAlert');
const showAlert = (msg, type = 'error') => {
  alertBox.innerHTML = msg ? `<div class="callout ${type} form-alert">${icon(type === 'error' ? 'alert' : 'info')}<span>${msg}</span></div>` : '';
};
if (qp('expired')) showAlert('Your session has expired. Please sign in again.', 'warn');
if (qp('loggedout')) showAlert('You have been signed out.', 'success');
if (qp('reset')) showAlert('Demo data has been reset to the original library.', 'success');
if (DEMO_MODE && !alertBox.innerHTML) {
  alertBox.innerHTML = `<div class="callout gold form-alert">${icon('info')}<span><strong>Live demo</strong> — SmartLib runs entirely in your browser here; your changes are saved only on this device.</span></div>`;
}

// Tabs
const loginForm = document.getElementById('loginForm');
const registerForm = document.getElementById('registerForm');
document.querySelectorAll('.tab').forEach((t) =>
  t.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === t));
    const reg = t.dataset.tab === 'register';
    loginForm.classList.toggle('hidden', reg);
    registerForm.classList.toggle('hidden', !reg);
    document.getElementById('formTitle').textContent = reg ? 'Create your account' : 'Welcome back';
    document.getElementById('formSub').textContent = reg ? 'Students get a library membership instantly.' : loginSubtitle();
    showAlert('');
    (reg ? registerForm : loginForm).querySelector('input').focus();
  })
);

async function login(email, password, btn) {
  await withLoading(btn, async () => {
    try {
      const { token, user } = await api.post('/auth/login', { email, password });
      session.save(token, user);
      toast(`Welcome, ${user.name.split(' ')[0]}!`, 'success', 'Signed in');
      setTimeout(goNext, 350);
    } catch (e) {
      showAlert(e.message);
      loginForm.querySelector('.glass, form')?.animate([{ transform: 'translateX(0)' }, { transform: 'translateX(-6px)' }, { transform: 'translateX(6px)' }, { transform: 'translateX(0)' }], { duration: 300 });
    }
  });
}

loginForm.addEventListener('submit', (e) => {
  e.preventDefault();
  showAlert('');
  if (!validateForm(loginForm)) return;
  const d = formData(loginForm);
  login(d.email, d.password, document.getElementById('loginBtn'));
});

registerForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  showAlert('');
  const ok = validateForm(registerForm, (d) => (d.password !== d.password2 ? { password2: 'Passwords do not match' } : {}));
  if (!ok) return;
  const { password2, ...d } = formData(registerForm);
  await withLoading(document.getElementById('registerBtn'), async () => {
    try {
      const { token, user } = await api.post('/auth/register', d);
      session.save(token, user);
      toast(`Membership ${user.member?.memberId} created`, 'success', 'Welcome to SmartLib');
      setTimeout(goNext, 500);
    } catch (err) {
      applyServerErrors(registerForm, err);
    }
  });
});

// Student / Staff-Librarian switch: students can self-register; staff accounts are created by an admin.
const ROLE_KEY = 'smartlib-login-role';
let roleMode = 'student';
try {
  if (localStorage.getItem(ROLE_KEY) === 'staff') roleMode = 'staff';
} catch {
  /* storage unavailable */
}
function loginSubtitle() {
  return roleMode === 'staff' ? 'Sign in to manage the catalogue, circulation and fines.' : 'Sign in to borrow, reserve and track your loans.';
}
function applyRoleMode(mode) {
  roleMode = mode;
  try {
    localStorage.setItem(ROLE_KEY, mode);
  } catch {
    /* storage unavailable */
  }
  const staff = mode === 'staff';
  document.querySelectorAll('[data-role-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.roleMode === mode)));
  if (staff && !registerForm.classList.contains('hidden')) document.querySelector('.tab[data-tab="login"]').click();
  document.querySelector('.tabs').classList.toggle('hidden', staff);
  if (registerForm.classList.contains('hidden')) document.getElementById('formSub').textContent = loginSubtitle();
  emailInput.placeholder = staff ? 'you@library.com or user ID' : 'you@college.edu or user ID';
}
document.querySelectorAll('[data-role-mode]').forEach((b) => b.addEventListener('click', () => applyRoleMode(b.dataset.roleMode)));
applyRoleMode(roleMode);

// Soft animated particles
(function particles() {
  const c = document.getElementById('particles');
  const ctx = c.getContext('2d');
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let w;
  let hgt;
  let pts = [];
  const resize = () => {
    w = c.width = c.offsetWidth * devicePixelRatio;
    hgt = c.height = c.offsetHeight * devicePixelRatio;
    const n = Math.min(70, Math.round((c.offsetWidth * c.offsetHeight) / 22000));
    pts = Array.from({ length: n }, () => ({ x: Math.random() * w, y: Math.random() * hgt, vx: (Math.random() - 0.5) * 0.25, vy: (Math.random() - 0.5) * 0.25, r: Math.random() * 1.6 + 0.6, gold: Math.random() < 0.12 }));
  };
  const draw = () => {
    ctx.clearRect(0, 0, w, hgt);
    const d = devicePixelRatio;
    for (let i = 0; i < pts.length; i += 1) {
      const p = pts[i];
      p.x = (p.x + p.vx * d + w) % w;
      p.y = (p.y + p.vy * d + hgt) % hgt;
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.r * d, 0, Math.PI * 2);
      ctx.fillStyle = p.gold ? 'rgba(212,167,44,.7)' : 'rgba(94,234,212,.55)';
      ctx.fill();
      for (let j = i + 1; j < pts.length; j += 1) {
        const q = pts[j];
        const dist = Math.hypot(p.x - q.x, p.y - q.y);
        if (dist < 130 * d) {
          ctx.strokeStyle = `rgba(20,184,166,${0.12 * (1 - dist / (130 * d))})`;
          ctx.lineWidth = d;
          ctx.beginPath();
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(q.x, q.y);
          ctx.stroke();
        }
      }
    }
    if (!reduce) requestAnimationFrame(draw);
  };
  resize();
  window.addEventListener('resize', resize);
  draw();
})();
