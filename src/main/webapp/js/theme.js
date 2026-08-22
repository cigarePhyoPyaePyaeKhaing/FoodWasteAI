/**
 * FoodWaste AI - Global Theme Manager
 * Supports: Light (Pale Blue Bubble/Glass), Dark (Deep Navy Bubble/Glass), System
 * Persists to localStorage key: foodwaste_theme
 * Dispatches: 'foodwaste:themechange' custom event
 */
const ThemeManager = {
  STORAGE_KEY: 'foodwaste_theme',
  mediaQuery: null,

  /**
   * Fast synchronous early theme application to prevent flash of unstyled theme
   */
  applyEarlyTheme() {
    try {
      const saved = localStorage.getItem('foodwaste_theme') || 'system';
      let resolved = saved;
      if (saved === 'system') {
        const isDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        resolved = isDark ? 'dark' : 'light';
      }
      document.documentElement.setAttribute('data-theme', resolved);
      document.documentElement.setAttribute('data-theme-mode', saved);
    } catch (e) {
      document.documentElement.setAttribute('data-theme', 'light');
      document.documentElement.setAttribute('data-theme-mode', 'system');
    }
  },

  init() {
    this.mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    
    // Listen for OS color scheme changes when mode is 'system'
    const handleOSChange = () => {
      if (this.getThemeMode() === 'system') {
        this.applyTheme('system', true);
      }
    };

    if (this.mediaQuery.addEventListener) {
      this.mediaQuery.addEventListener('change', handleOSChange);
    } else if (this.mediaQuery.addListener) {
      this.mediaQuery.addListener(handleOSChange);
    }

    const currentMode = this.getThemeMode();
    this.applyTheme(currentMode, false);
    this.renderThemeSelectors();

    // Listen for language changes to re-render switcher labels if needed
    window.addEventListener('languageChanged', () => {
      this.updateSwitcherUI();
    });
  },

  getThemeMode() {
    try {
      const saved = localStorage.getItem(this.STORAGE_KEY);
      if (saved === 'light' || saved === 'dark' || saved === 'system') {
        return saved;
      }
    } catch (e) {}
    return 'system';
  },

  getResolvedTheme() {
    const mode = this.getThemeMode();
    if (mode === 'system') {
      const mq = this.mediaQuery || (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)'));
      return (mq && mq.matches) ? 'dark' : 'light';
    }
    return mode;
  },

  isDark() {
    return this.getResolvedTheme() === 'dark';
  },

  setTheme(mode) {
    if (mode !== 'light' && mode !== 'dark' && mode !== 'system') return;
    try {
      localStorage.setItem(this.STORAGE_KEY, mode);
    } catch (e) {}
    this.applyTheme(mode, true);
  },

  applyTheme(mode, dispatchEvent = true) {
    let resolved = mode;
    if (mode === 'system') {
      const mq = this.mediaQuery || (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)'));
      resolved = (mq && mq.matches) ? 'dark' : 'light';
    }

    document.documentElement.setAttribute('data-theme', resolved);
    document.documentElement.setAttribute('data-theme-mode', mode);

    this.updateSwitcherUI();

    if (dispatchEvent) {
      const eventDetail = { theme: resolved, mode: mode, isDark: resolved === 'dark' };
      window.dispatchEvent(new CustomEvent('foodwaste:themechange', { detail: eventDetail }));
      window.dispatchEvent(new CustomEvent('themeChanged', { detail: eventDetail }));
    }
  },

  toggleTheme() {
    const current = this.getResolvedTheme();
    this.setTheme(current === 'dark' ? 'light' : 'dark');
  },

  renderThemeSelectors() {
    // 1. Sidebar Theme Selector (Desktop / Tablet)
    const sidebarContainer = document.getElementById('sidebar-theme-container');
    if (sidebarContainer) {
      sidebarContainer.innerHTML = this.getDesktopSelectorMarkup();
    }

    // 2. Topbar Theme Switcher (Header / Mobile Topbar)
    const topbarContainer = document.getElementById('topbar-theme-container');
    if (topbarContainer) {
      topbarContainer.innerHTML = this.getCompactSelectorMarkup();
    }

    // 3. Login Page Theme Switcher
    const loginContainer = document.getElementById('login-theme-container');
    if (loginContainer) {
      loginContainer.innerHTML = this.getDesktopSelectorMarkup();
    }

    this.updateSwitcherUI();
  },

  getDesktopSelectorMarkup() {
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    return `
      <div class="theme-switch-bubble" role="group" aria-label="Theme selection">
        <button type="button" class="theme-btn" data-theme-val="light" onclick="ThemeManager.setTheme('light')" title="${isMm ? 'အလင်း အသွင်အပြင်' : 'Light Mode'}" aria-label="Light mode">
          <span class="theme-btn-icon">☀️</span>
          <span class="theme-btn-text" data-i18n="theme.light">Light</span>
        </button>
        <button type="button" class="theme-btn" data-theme-val="dark" onclick="ThemeManager.setTheme('dark')" title="${isMm ? 'အမှောင် အသွင်အပြင်' : 'Dark Mode'}" aria-label="Dark mode">
          <span class="theme-btn-icon">🌙</span>
          <span class="theme-btn-text" data-i18n="theme.dark">Dark</span>
        </button>
        <button type="button" class="theme-btn" data-theme-val="system" onclick="ThemeManager.setTheme('system')" title="${isMm ? 'စနစ်အတိုင်း' : 'System Theme'}" aria-label="System mode">
          <span class="theme-btn-icon">💻</span>
          <span class="theme-btn-text" data-i18n="theme.system">System</span>
        </button>
      </div>
    `;
  },

  getCompactSelectorMarkup() {
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    const mode = this.getThemeMode();
    const resolved = this.getResolvedTheme();
    let nextMode = 'dark';
    if (mode === 'light') nextMode = 'dark';
    else if (mode === 'dark') nextMode = 'system';
    else nextMode = 'light';

    return `
      <button type="button" class="btn-bubble btn-glass-subtle btn-sm-bubble theme-toggle-btn" onclick="ThemeManager.setTheme('${nextMode}')" title="${isMm ? 'အသွင်အပြင် ပြောင်းရန်' : 'Toggle Theme'}" aria-label="Toggle visual theme">
        <span class="theme-icon-light" style="display:${resolved === 'light' ? 'inline-block' : 'none'};">☀️</span>
        <span class="theme-icon-dark" style="display:${resolved === 'dark' ? 'inline-block' : 'none'};">🌙</span>
        <span class="theme-mode-tag" style="font-size:0.72rem; font-weight:700; text-transform:uppercase; margin-left:3px;">${mode === 'system' ? 'Auto' : mode}</span>
      </button>
    `;
  },

  updateSwitcherUI() {
    const mode = this.getThemeMode();
    const resolved = this.getResolvedTheme();

    document.querySelectorAll('.theme-switch-bubble').forEach(group => {
      group.querySelectorAll('.theme-btn').forEach(btn => {
        const val = btn.getAttribute('data-theme-val');
        if (val === mode) {
          btn.classList.add('active');
          btn.setAttribute('aria-pressed', 'true');
        } else {
          btn.classList.remove('active');
          btn.setAttribute('aria-pressed', 'false');
        }
      });
    });

    document.querySelectorAll('.theme-toggle-btn').forEach(btn => {
      const lightIcon = btn.querySelector('.theme-icon-light');
      const darkIcon = btn.querySelector('.theme-icon-dark');
      const tag = btn.querySelector('.theme-mode-tag');

      if (lightIcon) lightIcon.style.display = (resolved === 'light') ? 'inline-block' : 'none';
      if (darkIcon) darkIcon.style.display = (resolved === 'dark') ? 'inline-block' : 'none';
      if (tag) tag.textContent = (mode === 'system') ? 'Auto' : mode;

      let nextMode = 'dark';
      if (mode === 'light') nextMode = 'dark';
      else if (mode === 'dark') nextMode = 'system';
      else nextMode = 'light';
      btn.setAttribute('onclick', `ThemeManager.setTheme('${nextMode}')`);
    });
  }
};

// Execute early on script load
ThemeManager.applyEarlyTheme();

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    ThemeManager.init();
  });
} else {
  ThemeManager.init();
}
