/**
 * FoodWaste AI - Internationalization (i18n) Engine
 * Supports Seamless English (EN) & Professional Myanmar (MM) Switching
 * Includes Unit-Aware Aggregation formatting across all operational modules.
 */
const I18n = {
  currentLang: 'en',

  init() {
    const saved = localStorage.getItem('foodwaste_lang');
    if (saved === 'mm' || saved === 'my' || saved === 'en') {
      this.currentLang = (saved === 'my') ? 'mm' : saved;
    } else {
      this.currentLang = 'en';
    }

    this.renderLanguageSwitcher();
    this.applyTranslations();
  },

  getLanguage() {
    return this.currentLang;
  },

  isMyanmar() {
    return this.currentLang === 'mm' || this.currentLang === 'my';
  },

  setLanguage(lang) {
    if (lang !== 'en' && lang !== 'mm' && lang !== 'my') return;
    const normalized = (lang === 'my') ? 'mm' : lang;
    this.currentLang = normalized;
    localStorage.setItem('foodwaste_lang', normalized);
    this.applyTranslations();
    this.updateSwitcherUI();

    // Trigger custom event for dynamic controllers to re-render immediately
    window.dispatchEvent(new CustomEvent('languageChanged', { detail: { language: normalized } }));
  },

  toggleLanguage() {
    this.setLanguage(this.isMyanmar() ? 'en' : 'mm');
  },

  /**
   * Unit-Aware Aggregate Formatting Helper
   * Safely groups quantities by unit.
   * - Single unit: "4.0 liter", "3.0 kg", "12 pieces"
   * - Mixed units: "5.0 liter • 3.0 kg • 12 pieces" (never adds incompatible numbers together!)
   * - Empty: "0.0" or "0.0 <defaultUnit>"
   */
  formatUnitAggregate(items, getQuantity, getUnit, defaultUnit = '') {
    if (!items || items.length === 0) {
      return defaultUnit ? `0.0 ${defaultUnit}` : '0.0';
    }

    const totalsByUnit = {};
    items.forEach(item => {
      let rawQty = 0;
      if (getQuantity) {
        rawQty = getQuantity(item);
      } else if (item.quantity !== undefined) {
        rawQty = item.quantity;
      } else if (item.quantitySold !== undefined) {
        rawQty = item.quantitySold;
      } else if (item.quantityWasted !== undefined) {
        rawQty = item.quantityWasted;
      } else if (item.predictedWasteQuantity !== undefined) {
        rawQty = item.predictedWasteQuantity;
      } else if (item.predictedWasteQty !== undefined) {
        rawQty = item.predictedWasteQty;
      } else if (item.stock !== undefined) {
        rawQty = item.stock;
      }
      const qty = Number(rawQty) || 0;

      let rawUnit = '';
      if (getUnit) {
        rawUnit = getUnit(item);
      } else if (item.unit) {
        rawUnit = item.unit;
      } else if (item.foodUnit) {
        rawUnit = item.foodUnit;
      } else {
        rawUnit = defaultUnit || 'units';
      }
      const unit = (rawUnit && String(rawUnit).trim()) ? String(rawUnit).trim() : (defaultUnit || 'units');

      if (qty > 0 || Object.keys(totalsByUnit).length === 0) {
        totalsByUnit[unit] = (totalsByUnit[unit] || 0) + qty;
      }
    });

    const units = Object.keys(totalsByUnit);
    if (units.length === 0) {
      return defaultUnit ? `0.0 ${defaultUnit}` : '0.0';
    }

    const formatVal = (val, u) => {
      const num = Number(val) || 0;
      const lower = String(u).toLowerCase();
      if ((lower === 'pieces' || lower === 'piece' || lower === 'units') && num % 1 === 0) {
        return Math.round(num).toString();
      }
      return num.toFixed(1);
    };

    if (units.length === 1) {
      const u = units[0];
      const val = totalsByUnit[u];
      return `${formatVal(val, u)} ${u}`;
    }

    // Mixed units: Render compact unit-specific totals e.g. "5.0 liter • 3.0 kg • 12 pieces"
    return units.map(u => `${formatVal(totalsByUnit[u], u)} ${u}`).join(' • ');
  },

  /**
   * Resolves dynamic bilingual field from database objects
   * e.g. I18n.getDynamic(recommendation, 'title')
   */
  getDynamic(obj, field) {
    if (!obj) return '';
    if (this.isMyanmar()) {
      return obj[field + '_my'] || obj[field + 'My'] || obj[field + '_en'] || obj[field + 'En'] || obj[field] || '';
    }
    return obj[field + '_en'] || obj[field + 'En'] || obj[field] || '';
  },

  translateRisk(risk) {
    if (!risk) return '';
    const upper = String(risk).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'HIGH') return 'အန္တရာယ်မြင့်';
      if (upper === 'MEDIUM' || upper === 'MED') return 'အလယ်အလတ်အန္တရာယ်';
      if (upper === 'LOW') return 'အန္တရာယ်နည်း';
    }
    if (upper === 'HIGH') return 'HIGH RISK';
    if (upper === 'MEDIUM' || upper === 'MED') return 'MEDIUM RISK';
    if (upper === 'LOW') return 'LOW RISK';
    return upper;
  },

  translateCategory(cat) {
    if (!cat) return '';
    const upper = String(cat).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'URGENT') return 'အရေးပေါ်';
      if (upper === 'IMPORTANT') return 'အရေးကြီး';
      if (upper === 'OPTIMIZATION') return 'စီမံညှိနှိုင်းမှု';
      if (upper === 'REDISTRIBUTION') return 'ပိုလျှံအစားအစာ ပြန်လည်ဖြန့်ဝေမှု';
    }
    if (upper === 'URGENT') return 'URGENT';
    if (upper === 'IMPORTANT') return 'IMPORTANT';
    if (upper === 'OPTIMIZATION') return 'OPTIMIZATION';
    if (upper === 'REDISTRIBUTION') return 'REDISTRIBUTION';
    return upper;
  },

  translateStatus(status) {
    if (!status) return '';
    const upper = String(status).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'OK') return 'ပုံမှန်ကောင်းမွန်';
      if (upper === 'NEAR_EXPIRY' || upper === 'NEAREXPIRY') return 'သက်တမ်းကုန်ရန်နီး';
      if (upper === 'EXPIRED') return 'သက်တမ်းကုန်ပြီး';
      if (upper === 'LOW_STOCK' || upper === 'LOWSTOCK') return 'လက်ကျန်နည်း';
      if (upper === 'PENDING') return 'စောင့်ဆိုင်းဆဲ';
      if (upper === 'CONFIRMED') return 'အတည်ပြုပြီး';
      if (upper === 'COLLECTED') return 'လက်ခံရယူပြီး';
      if (upper === 'COMPLETED') return 'ပြီးစီး';
      if (upper === 'ACCEPTED') return 'လက်ခံပြီး';
      if (upper === 'DISMISSED') return 'ပယ်ဖျက်ပြီး';
      if (upper === 'CANCELLED') return 'ပယ်ဖျက်ပြီး';
      if (upper === 'ACTIVE') return 'အသုံးပြုဆဲ';
      if (upper === 'INACTIVE') return 'ပိတ်ထားသည်';
    }
    if (upper === 'OK') return 'OK';
    if (upper === 'NEAR_EXPIRY' || upper === 'NEAREXPIRY') return 'NEAR EXPIRY';
    if (upper === 'EXPIRED') return 'EXPIRED';
    if (upper === 'LOW_STOCK' || upper === 'LOWSTOCK') return 'LOW STOCK';
    if (upper === 'PENDING') return 'PENDING';
    if (upper === 'CONFIRMED') return 'CONFIRMED';
    if (upper === 'COLLECTED') return 'COLLECTED';
    if (upper === 'COMPLETED') return 'COMPLETED';
    if (upper === 'ACCEPTED') return 'ACCEPTED';
    if (upper === 'DISMISSED') return 'DISMISSED';
    if (upper === 'CANCELLED') return 'CANCELLED';
    if (upper === 'ACTIVE') return 'ACTIVE';
    if (upper === 'INACTIVE') return 'INACTIVE';
    return upper;
  },

  translatePriority(priority) {
    if (!priority) return '';
    const upper = String(priority).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'IMMEDIATE_USE' || upper === 'IMMEDIATE') return 'ချက်ချင်းအသုံးပြုရန်';
      if (upper === 'HIGH_PRIORITY' || upper === 'HIGH') return 'ဦးစားပေးအဆင့်မြင့်';
      if (upper === 'MODERATE_PRIORITY' || upper === 'MODERATE' || upper === 'MEDIUM') return 'အလယ်အလတ် ဦးစားပေး';
      if (upper === 'STANDARD' || upper === 'NORMAL') return 'ပုံမှန်အဆင့်';
      if (upper === 'DISPOSE_OR_COMPOST' || upper === 'COMPOST') return 'စွန့်ပစ် သို့မဟုတ် မြေဆွေးပြုလုပ်ရန်';
    }
    if (upper === 'IMMEDIATE_USE' || upper === 'IMMEDIATE') return 'IMMEDIATE USE';
    if (upper === 'HIGH_PRIORITY' || upper === 'HIGH') return 'HIGH PRIORITY';
    if (upper === 'MODERATE_PRIORITY' || upper === 'MODERATE' || upper === 'MEDIUM') return 'MODERATE PRIORITY';
    if (upper === 'STANDARD' || upper === 'NORMAL') return 'STANDARD';
    if (upper === 'DISPOSE_OR_COMPOST' || upper === 'COMPOST') return 'DISPOSE / COMPOST';
    return upper;
  },

  translateWasteReason(reason) {
    if (!reason) return '';
    const upper = String(reason).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'OVERPRODUCTION') return 'ပိုလျှံထုတ်လုပ်မှု';
      if (upper === 'EXPIRED') return 'သက်တမ်းကုန်ဆုံးခြင်း';
      if (upper === 'UNSOLD') return 'ညနေခင်း ရောင်းမကုန်သော ပစ္စည်း';
      if (upper === 'SPOILED') return 'သိုလှောင်မှု ချွတ်ယွင်းပျက်စီးခြင်း';
      if (upper === 'DAMAGED') return 'ကိုင်တွယ်စဉ် ထိခိုက်ပျက်စီးခြင်း';
      if (upper === 'PREPARATION_WASTE') return 'ချက်ပြုတ်ပြင်ဆင်မှု အလေအလွင့်';
      if (upper === 'OTHER') return 'အခြားအကြောင်းရင်း';
    }
    if (upper === 'OVERPRODUCTION') return 'Overproduction';
    if (upper === 'EXPIRED') return 'Expired Shelf Life';
    if (upper === 'UNSOLD') return 'Unsold Surplus';
    if (upper === 'SPOILED') return 'Spoiled Storage';
    if (upper === 'DAMAGED') return 'Handling Damage';
    if (upper === 'PREPARATION_WASTE') return 'Preparation Trimming';
    if (upper === 'OTHER') return 'Other Reason';
    return reason;
  },

  translateFoodCategory(category) {
    if (!category) return '';
    const upper = String(category).toUpperCase();
    if (this.isMyanmar()) {
      if (upper === 'POULTRY') return 'ကြက်/ဘဲ/ငှက် အသား';
      if (upper === 'PRODUCE') return 'ဟင်းသီးဟင်းရွက်နှင့် သစ်သီးဝလံ';
      if (upper === 'SEAFOOD') return 'ပင်လယ်စာ';
      if (upper === 'GRAINS') return 'ဂျုံနှင့် နှံစားသီးနှံ';
      if (upper === 'DAIRY') return 'နို့နှင့် နို့ထွက်ပစ္စည်း';
      if (upper === 'BAKERY') return 'မုန့်ဖုတ်ထုတ်ကုန်';
      if (upper === 'MEAT') return 'အသား';
    }
    return category;
  },

  translateError(err) {
    if (!err) return 'An error occurred';
    const msg = typeof err === 'string' ? err : (err.message || err.error || JSON.stringify(err));
    if (this.isMyanmar()) {
      if (msg.includes('Insufficient stock') || msg.includes('insufficient stock')) return 'ပစ္စည်းလက်ကျန် မလုံလောက်ပါ။ ' + msg;
      if (msg.includes('Cannot record sale for expired') || msg.includes('expired food item')) return 'သက်တမ်းကုန်ဆုံးသွားသော ကုန်ပစ္စည်းကို ရောင်းချ၍မရပါ';
      if (msg.includes('Recipient not found') || msg.includes('inactive')) return 'ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်းကို ရှာမတွေ့ပါ သို့မဟုတ် ပိတ်ထားပါသည်';
      if (msg.includes('Food item not found')) return 'ကုန်ပစ္စည်းကို ရှာမတွေ့ပါ';
      if (msg.includes('greater than zero') || msg.includes('greater than 0')) return 'ပမာဏသည် သုညထက် ကြီးရပါမည်';
      if (msg.includes('Invalid credentials')) return 'အသုံးပြုသူအမည် သို့မဟုတ် လျှို့ဝှက်နံပါတ် မှားယွင်းနေပါသည်';
      if (msg.includes('Unauthorized') || msg.includes('unauthorized')) return 'ဝင်ရောက်ခွင့် မရှိပါ';
      if (msg.includes('Recipient ID is required')) return 'ပရဟိတ မိတ်ဖက်အဖွဲ့အစည်း ID လိုအပ်ပါသည်';
      if (msg.includes('Food item ID is required')) return 'ကုန်ပစ္စည်း ID လိုအပ်ပါသည်';
      if (msg.includes('Network') || msg.includes('Failed to fetch')) return 'ကွန်ရက် ချိတ်ဆက်မှု မအောင်မြင်ပါ';
    }
    return msg;
  },

  t(key, defaultText = '') {
    const dict = this.isMyanmar() ? window.I18N_MM : window.I18N_EN;
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
    const pageTitleKey = document.body ? document.body.getAttribute('data-i18n-page-title') : null;
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
        topbarRight.insertBefore(container, topbarRight.firstChild);
      } else {
        const loginCard = document.getElementById('login-lang-container') || document.querySelector('.brand-hero-box');
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
      const isEn = this.currentLang === 'en';
      const isMm = this.isMyanmar();
      container.innerHTML = `
        <div class="lang-switch-bubble">
          <button type="button" id="lang-btn-en" onclick="I18n.setLanguage('en')" class="lang-btn ${isEn ? 'active' : ''}">
            🌐 English
          </button>
          <button type="button" id="lang-btn-mm" onclick="I18n.setLanguage('mm')" class="lang-btn ${isMm ? 'active' : ''}">
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
        btnEn.classList.add('active');
        btnMm.classList.remove('active');
      } else {
        btnMm.classList.add('active');
        btnEn.classList.remove('active');
      }
    }
  }
};

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    I18n.init();
  });
} else {
  I18n.init();
}
