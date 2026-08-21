/**
 * FoodWaste AI - Authentication & Role Manager
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

  async logout() {
    try {
      if (typeof API !== 'undefined') {
        await API.post('/api/auth/logout', {});
      }
    } catch (e) {
      console.warn('Logout API notification error:', e);
    }
    localStorage.removeItem('foodwaste_user');
    localStorage.removeItem('foodwaste_token');
    document.cookie = 'foodwaste_session=; Path=/; Max-Age=0; SameSite=Lax';
    window.location.replace('/index.html');
  },

  isAdmin() {
    const u = this.getUser();
    return !!(u && u.role === 'ADMIN');
  },

  isStaff() {
    const u = this.getUser();
    return !!(u && u.role === 'STAFF');
  },

  requireAuth(adminOnly = false) {
    const path = window.location.pathname;
    const isPublic = path === '/' || path.endsWith('/index.html') || path.endsWith('/index.htm');
    if (isPublic) return;

    if (!this.isAuthenticated()) {
      window.location.replace('/index.html');
      return;
    }

    if (adminOnly && !this.isAdmin()) {
      window.location.replace('/dashboard.html');
    }
  },

  initUI() {
    const path = window.location.pathname;
    const isPublic = path === '/' || path.endsWith('/index.html') || path.endsWith('/index.htm');

    if (!isPublic) {
      if (!this.isAuthenticated()) {
        window.location.replace('/index.html');
        return;
      }
      if (path.endsWith('/users.html') && !this.isAdmin()) {
        window.location.replace('/dashboard.html');
        return;
      }
    }

    const user = this.getUser();
    if (user) {
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
    }

    // Initialize Mobile Hamburger Menu
    const toggleBtn = document.getElementById('mobile-toggle-btn');
    const sidebar = document.querySelector('.sidebar');
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

// Back-forward cache protection
window.addEventListener('pageshow', (event) => {
  const path = window.location.pathname;
  const isPublic = path === '/' || path.endsWith('/index.html') || path.endsWith('/index.htm');
  if (!isPublic && !Auth.isAuthenticated()) {
    window.location.replace('/index.html');
  }
});

document.addEventListener('DOMContentLoaded', () => {
  Auth.initUI();
});
