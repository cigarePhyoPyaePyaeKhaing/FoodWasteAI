/**
 * FoodWaste AI - Authentication & Role Manager
 */
const Auth = {
  getUser() {
    const raw = localStorage.getItem('foodwaste_user');
    if (!raw) return { username: 'admin', fullName: 'Restaurant Manager', role: 'ADMIN' };
    try {
      return JSON.parse(raw);
    } catch {
      return { username: 'admin', fullName: 'Restaurant Manager', role: 'ADMIN' };
    }
  },

  setUser(user, token) {
    localStorage.setItem('foodwaste_user', JSON.stringify(user));
    if (token) localStorage.setItem('foodwaste_token', token);
  },

  logout() {
    localStorage.removeItem('foodwaste_user');
    localStorage.removeItem('foodwaste_token');
    window.location.href = '/index.html';
  },

  isAdmin() {
    const u = this.getUser();
    return u && u.role === 'ADMIN';
  },

  isStaff() {
    const u = this.getUser();
    return u && u.role === 'STAFF';
  },

  initUI() {
    const user = this.getUser();
    const nameEl = document.getElementById('current-user-name');
    const roleEl = document.getElementById('current-user-role');
    const avatarEl = document.getElementById('current-user-avatar');

    if (nameEl) nameEl.textContent = user.fullName || user.username;
    if (roleEl) roleEl.textContent = user.role || 'ADMIN';
    if (avatarEl) avatarEl.textContent = (user.fullName || user.username || 'U').charAt(0).toUpperCase();

    // Role-based visibility for Admin-only nav items
    if (!this.isAdmin()) {
      document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
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

document.addEventListener('DOMContentLoaded', () => {
  Auth.initUI();
});
