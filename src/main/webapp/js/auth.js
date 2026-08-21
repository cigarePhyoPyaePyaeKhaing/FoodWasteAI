/**
 * FoodWaste AI - Authentication & Role Manager
 * Authoritative source of truth backed by server-side session verification.
 */
const Auth = {
  getUser() {
    const raw = localStorage.getItem('foodwaste_user');
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  },

  getToken() {
    return localStorage.getItem('foodwaste_token');
  },

  isAuthenticated() {
    return !!(this.getToken() && this.getUser());
  },

  setUser(user, token) {
    if (user) {
      localStorage.setItem('foodwaste_user', JSON.stringify(user));
    }
    if (token) {
      localStorage.setItem('foodwaste_token', token);
    }
  },

  clearAuth() {
    localStorage.removeItem('foodwaste_user');
    localStorage.removeItem('foodwaste_token');
    document.cookie = 'foodwaste_session=; Path=/; Max-Age=0; SameSite=Lax';
    document.cookie = 'token=; Path=/; Max-Age=0; SameSite=Lax';
  },

  /**
   * Asynchronously verifies the current session against the backend authoritative state.
   * If valid: updates stored user and returns user object.
   * If invalid/expired: clears stored auth and returns null.
   */
  async checkSession() {
    try {
      const token = this.getToken();
      const headers = { 'Accept': 'application/json' };
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const res = await fetch('/api/auth/me', {
        method: 'GET',
        headers,
        credentials: 'same-origin'
      });

      if (res.ok) {
        const json = await res.json();
        if (json && json.success && json.data) {
          const user = json.data.user || json.data;
          const freshToken = json.data.token || token;
          this.setUser(user, freshToken);
          return user;
        }
      }
    } catch (e) {
      console.warn('Session verification error:', e);
    }

    // Unauthenticated or server rejected session
    this.clearAuth();
    return null;
  },

  async logout() {
    try {
      if (typeof API !== 'undefined') {
        await API.post('/api/auth/logout', {});
      }
    } catch (e) {
      console.warn('Logout API notification error:', e);
    } finally {
      this.clearAuth();
      window.location.replace('/index.html');
    }
  },

  isAdmin() {
    const u = this.getUser();
    return !!(u && u.role === 'ADMIN');
  },

  isStaff() {
    const u = this.getUser();
    return !!(u && u.role === 'STAFF');
  },

  async initUI() {
    const path = window.location.pathname;
    const isPublic = path === '/' || path.endsWith('/index.html') || path.endsWith('/index.htm');

    if (isPublic) {
      // On login page: check if valid session exists on the backend before redirecting
      const user = await this.checkSession();
      if (user) {
        window.location.replace('/dashboard.html');
      }
      return;
    }

    // On protected page: verify session
    const user = await this.checkSession();
    if (!user) {
      this.clearAuth();
      window.location.replace('/index.html');
      return;
    }

    if (path.endsWith('/users.html') && !this.isAdmin()) {
      window.location.replace('/dashboard.html');
      return;
    }

    // Populate user profile info in sidebar
    const nameEl = document.getElementById('current-user-name');
    const roleEl = document.getElementById('current-user-role');
    const avatarEl = document.getElementById('current-user-avatar');

    if (nameEl) nameEl.textContent = user.fullName || user.username;
    if (roleEl) roleEl.textContent = user.role || 'STAFF';
    if (avatarEl) avatarEl.textContent = (user.fullName || user.username || 'U').charAt(0).toUpperCase();

    // Role-based visibility for Admin-only nav items
    if (!this.isAdmin()) {
      document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
    }

    // Initialize Mobile Hamburger Menu
    const toggleBtn = document.getElementById('mobile-toggle-btn');
    const sidebar = document.querySelector('.sidebar') || document.querySelector('.sidebar-float');
    const backdrop = document.querySelector('.sidebar-backdrop');

    if (toggleBtn && sidebar) {
      toggleBtn.addEventListener('click', () => {
        sidebar.classList.toggle('open');
        if (backdrop) backdrop.classList.toggle('active');
      });
    }

    if (backdrop) {
      backdrop.addEventListener('click', () => {
        if (sidebar) sidebar.classList.remove('open');
        backdrop.classList.remove('active');
      });
    }
  }
};

// Back-forward cache protection: re-validate with backend on bfcache restore
window.addEventListener('pageshow', async (event) => {
  const path = window.location.pathname;
  const isPublic = path === '/' || path.endsWith('/index.html') || path.endsWith('/index.htm');
  if (!isPublic) {
    const user = await Auth.checkSession();
    if (!user) {
      window.location.replace('/index.html');
    }
  }
});

document.addEventListener('DOMContentLoaded', () => {
  Auth.initUI();
});
