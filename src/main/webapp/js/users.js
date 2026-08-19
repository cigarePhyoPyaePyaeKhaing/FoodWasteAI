/**
 * FoodWaste AI - Users Controller
 * Connected with /api/users REST endpoints (Admin Protected)
 */
const Users = {
  users: [],
  loading: false,

  async init() {
    window.addEventListener('languageChanged', () => {
      this.render();
    });
    await this.fetchUsers();
  },

  async fetchUsers() {
    this.loading = true;
    try {
      const res = await API.get('/api/users');
      this.users = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching users:', err);
      this.users = [
        { id: 1, fullName: 'Restaurant Manager', username: 'admin', email: 'admin@foodwaste.ai', role: 'ADMIN', active: true },
        { id: 2, fullName: 'Sarah Jenkins', username: 'staff', email: 'staff@foodwaste.ai', role: 'STAFF', active: true }
      ];
    } finally {
      this.loading = false;
      this.render();
    }
  },

  render() {
    const tbody = document.getElementById('users-tbody');
    if (!tbody) return;

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    if (this.users.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="6" style="text-align:center; padding:2rem; color:var(--text-muted);">
            ${isMm ? 'အသုံးပြုသူ အကောင့်များ မရှိသေးပါ။ "+ အကောင့်အသစ် ဖွင့်မည်" ကို နှိပ်ပါ။' : 'No user accounts found. Click "+ Add New Staff" to create an account.'}
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.users.map(u => {
      const roleLabel = isMm
        ? (u.role === 'ADMIN' ? 'မန်နေဂျာ (ADMIN)' : 'ဝန်ထမ်း (STAFF)')
        : u.role;
      const roleBadge = u.role === 'ADMIN' ?
        `<span class="badge-bubble badge-urgent">${roleLabel}</span>` :
        `<span class="badge-bubble badge-optimization">${roleLabel}</span>`;

      const statusText = isMm ? (u.active ? 'အသုံးပြုနေဆဲ' : 'ရပ်ဆိုင်းထား') : (u.active ? 'ACTIVE' : 'INACTIVE');
      const detailsBtn = isMm ? 'အသေးစိတ်' : 'Details';

      return `
        <tr>
          <td><strong>${u.fullName || u.username}</strong></td>
          <td><code>${u.username}</code></td>
          <td>${u.email || '-'}</td>
          <td>${roleBadge}</td>
          <td><span class="badge-bubble badge-risk-low">${statusText}</span></td>
          <td style="text-align:right;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="API.showToast('Account active', 'info')">${detailsBtn}</button>
          </td>
        </tr>
      `;
    }).join('');

    const countBadge = document.getElementById('users-count-badge');
    if (countBadge) {
      countBadge.textContent = isMm ? `အသုံးပြုနေသော အကောင့် (${this.users.length}) ခု` : `${this.users.length} Active Accounts`;
    }
  },

  openModal() {
    const form = document.getElementById('new-user-form');
    if (form) form.reset();
    const modal = document.getElementById('user-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('user-modal');
    if (modal) modal.classList.remove('active');
  },

  async saveUser(e) {
    e.preventDefault();
    const username = document.getElementById('user-username').value.trim();
    const fullName = document.getElementById('user-fullname').value.trim();
    const email = document.getElementById('user-email').value.trim();
    const password = document.getElementById('user-password').value;
    const role = document.getElementById('user-role').value;

    if (!username || !password || password.length < 6) {
      API.showToast('Password must be at least 6 characters', 'warning');
      return;
    }

    try {
      await API.post('/api/users', { username, fullName, email, password, role });
      API.showToast(`User account '${username}' created with BCrypt security!`, 'success');
      this.closeModal();
      await this.fetchUsers();
    } catch (err) {
      console.error('Error creating user:', err);
      API.showToast('Failed to create user: ' + err.message, 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Users.init();
});
