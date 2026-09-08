'use strict';
const Profile = {
  user: null,
  async load() {
    const status = document.getElementById('profile-status');
    const retry = document.getElementById('profile-retry');
    const content = document.getElementById('profile-content');
    content.hidden = true;
    retry.hidden = true;
    status.hidden = false;
    status.textContent = I18n.t('profile.loading');
    try {
      const response = await API.get('/api/auth/me');
      if (!response.success || !response.data?.email) throw new Error('Missing account');
      this.user = response.data;
      this.render();
      status.hidden = true;
      content.hidden = false;
    } catch {
      this.user = null;
      status.textContent = I18n.t('profile.error');
      retry.hidden = false;
    }
  },
  render() {
    if (!this.user) return;
    const name = this.user.fullName || this.user.email;
    document.getElementById('profile-name').textContent = name;
    document.getElementById('profile-email').textContent = this.user.email;
    document.getElementById('profile-role').textContent = I18n.t(this.user.role === 'ADMIN' ? 'profile.admin' : 'profile.staff');
    document.getElementById('profile-avatar').textContent = Array.from(name.trim())[0]?.toUpperCase() || '👤';
  }
};
document.getElementById('profile-retry').addEventListener('click', () => Profile.load());
window.addEventListener('languageChanged', () => Profile.render());
Profile.load();
