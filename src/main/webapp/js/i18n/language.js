/**
 * FoodWaste AI - Internationalization (i18n) Engine
 * Supports Seamless English (EN) & Professional Myanmar (MM) Switching
 */
const I18n = {
  currentLang: 'en',

  init() {
    const saved = localStorage.getItem('foodwaste_lang');
    if (saved === 'mm' || saved === 'en') {
      this.currentLang = saved;
    } else {
      this.currentLang = 'en';
    }

    this.renderLanguageSwitcher();
    this.applyTranslations();
  },

  getLanguage() {
    return this.currentLang;
  },

  setLanguage(lang) {
    if (lang !== 'en' && lang !== 'mm') return;
    this.currentLang = lang;
    localStorage.setItem('foodwaste_lang', lang);
    this.applyTranslations();
    this.updateSwitcherUI();

    // Trigger custom event for dynamic controllers to re-render
    window.dispatchEvent(new CustomEvent('languageChanged', { detail: { language: lang } }));
  },

  t(key, defaultText = '') {
    const dict = this.currentLang === 'mm' ? window.I18N_MM : window.I18N_EN;
    if (dict && dict[key]) {
      return dict[key];
    }
    // Fallback to English dictionary
    if (window.I18N_EN && window.I18N_EN[key]) {
      return window.I18N_EN[key];
    }
    return defaultText || key;
  },

  applyTranslations() {
    // 1. Translate elements with data-i18n attribute
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      if (key) {
        const translation = this.t(key);
        if (translation) {
          el.textContent = translation;
        }
      }
    });

    // 2. Translate placeholders
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
      const key = el.getAttribute('data-i18n-placeholder');
      if (key) {
        const translation = this.t(key);
        if (translation) {
          el.setAttribute('placeholder', translation);
        }
      }
    });

    // 3. Translate tooltips / titles
    document.querySelectorAll('[data-i18n-title]').forEach(el => {
      const key = el.getAttribute('data-i18n-title');
      if (key) {
        const translation = this.t(key);
        if (translation) {
          el.setAttribute('title', translation);
        }
      }
    });

    // 4. Update dynamic document title if specified
    const pageTitleKey = document.body.getAttribute('data-i18n-page-title');
    if (pageTitleKey) {
      document.title = "FoodWaste AI - " + this.t(pageTitleKey);
    }
  },

  renderLanguageSwitcher() {
    // Look for dedicated container or inject into topbar-right / login-box
    let container = document.getElementById('language-switcher-container');

    if (!container) {
      const topbarRight = document.querySelector('.topbar-right');
      if (topbarRight) {
        container = document.createElement('div');
        container.id = 'language-switcher-container';
        container.style.display = 'inline-flex';
        container.style.alignItems = 'center';
        container.style.marginRight = '0.75rem';
        topbarRight.insertBefore(container, topbarRight.firstChild);
      } else {
        const loginCard = document.querySelector('.brand-hero-box');
        if (loginCard) {
          container = document.createElement('div');
          container.id = 'language-switcher-container';
          container.style.display = 'flex';
          container.style.justifyContent = 'center';
          container.style.marginBottom = '1rem';
          loginCard.appendChild(container);
        }
      }
    }

    if (container) {
      container.innerHTML = `
        <div class="lang-switch-bubble" style="
          display: inline-flex;
          background: var(--bg-surface-glass);
          backdrop-filter: var(--glass-blur);
          -webkit-backdrop-filter: var(--glass-blur);
          border: 1px solid var(--glass-border);
          border-radius: var(--radius-pill);
          padding: 3px;
          gap: 2px;
          box-shadow: 0 2px 8px rgba(0,0,0,0.04);
        ">
          <button type="button" id="lang-btn-en" onclick="I18n.setLanguage('en')" style="
            border: none;
            cursor: pointer;
            padding: 4px 10px;
            font-size: 0.78rem;
            font-weight: 700;
            border-radius: var(--radius-pill);
            transition: all 0.2s ease;
            ${this.currentLang === 'en' ? 'background: var(--accent-yellow-400); color: var(--text-main); box-shadow: 0 1px 4px rgba(234,179,8,0.3);' : 'background: transparent; color: var(--text-muted);'}
          ">
            🌐 English
          </button>
          <button type="button" id="lang-btn-mm" onclick="I18n.setLanguage('mm')" style="
            border: none;
            cursor: pointer;
            padding: 4px 10px;
            font-size: 0.78rem;
            font-weight: 700;
            border-radius: var(--radius-pill);
            transition: all 0.2s ease;
            ${this.currentLang === 'mm' ? 'background: var(--accent-yellow-400); color: var(--text-main); box-shadow: 0 1px 4px rgba(234,179,8,0.3);' : 'background: transparent; color: var(--text-muted);'}
          ">
            မြန်မာ
          </button>
        </div>
      `;
    }
  },

  updateSwitcherUI() {
    const btnEn = document.getElementById('lang-btn-en');
    const btnMm = document.getElementById('lang-btn-mm');

    if (btnEn && btnMm) {
      if (this.currentLang === 'en') {
        btnEn.style.background = 'var(--accent-yellow-400)';
        btnEn.style.color = 'var(--text-main)';
        btnEn.style.boxShadow = '0 1px 4px rgba(234,179,8,0.3)';

        btnMm.style.background = 'transparent';
        btnMm.style.color = 'var(--text-muted)';
        btnMm.style.boxShadow = 'none';
      } else {
        btnMm.style.background = 'var(--accent-yellow-400)';
        btnMm.style.color = 'var(--text-main)';
        btnMm.style.boxShadow = '0 1px 4px rgba(234,179,8,0.3)';

        btnEn.style.background = 'transparent';
        btnEn.style.color = 'var(--text-muted)';
        btnEn.style.boxShadow = 'none';
      }
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  I18n.init();
});
