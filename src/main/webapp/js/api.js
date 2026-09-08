/**
 * FoodWaste AI - Central API Client
 * Wraps Fetch API with JSON envelopes, error handling, and toast alerts.
 */
const API = {
  baseUrl: '',
  // Event timestamps are UTC; pickupTime is a scheduled Yangon wall time.
  formatTimestamp(value, scheduled = false) {
    if (!value) return {date: '-', time: '', text: '-'};
    let iso = String(value).replace(' ', 'T');
    if (!/(Z|[+-]\d{2}:\d{2})$/.test(iso)) iso += scheduled ? '+06:30' : 'Z';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return {date: '-', time: '', text: '-'};
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {timeZone:'Asia/Yangon', year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(date).map(p=>[p.type,p.value]));
    const day = `${parts.year}-${parts.month}-${parts.day}`, time = `${parts.hour}:${parts.minute}`;
    return {date:day,time,text:`${day} ${time} (Yangon)`};
  },
  userMessage(message) {
    return /SQLException|stack trace|SWI-Prolog|Prolog|predicate|Exception|com\.foodwasteai/i.test(String(message))
      ? (typeof I18n !== 'undefined' && I18n.isMyanmar() ? 'လုပ်ဆောင်ချက် မအောင်မြင်ပါ။ ပြန်လည်ကြိုးစားပါ။' : 'Unable to complete this action. Please try again.') : message;
  },

  async request(endpoint, options = {}) {
    const defaultHeaders = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Cache-Control': 'no-cache',
      'Pragma': 'no-cache'
    };

    const config = {
      credentials: 'same-origin',
      ...options,
      headers: {
        ...defaultHeaders,
        ...options.headers
      }
    };

    if (config.body && typeof config.body === 'object' && !(config.body instanceof FormData)) {
      config.body = JSON.stringify(config.body);
    }

    try {
      const response = await fetch(this.baseUrl + endpoint, config);
      const data = await response.json().catch(() => null);

      if (!response.ok) {
        if (response.status === 401 && !window.location.pathname.includes('login') && !window.location.pathname.includes('register')) {
          window.location.replace('/login.html');
          return null;
        }
        const errorMsg = data && data.message ? data.message : `HTTP Error ${response.status}: ${response.statusText}`;
        const err = new Error(errorMsg);
        err.status = response.status;
        throw err;
      }

      if (!data || data.success === false) throw new Error(data?.message || "Unable to complete the request. Please try again.");
      return data;
    } catch (err) {
      console.error(`API Error [${endpoint}]:`, err);
      this.showToast(err.message, 'error');
      throw err;
    }
  },

  logout() {
    fetch('/api/auth/logout', { method: 'POST' }).finally(() => {
      window.location.replace('/login.html');
    });
  },

  get(endpoint, params = {}) {
    const url = new URL(this.baseUrl + endpoint, window.location.origin);
    Object.keys(params).forEach(key => url.searchParams.append(key, params[key]));
    return this.request(url.pathname + url.search, { method: 'GET', cache: 'no-store' });
  },

  post(endpoint, body) {
    return this.request(endpoint, { method: 'POST', body });
  },

  put(endpoint, body) {
    return this.request(endpoint, { method: 'PUT', body });
  },

  delete(endpoint) {
    return this.request(endpoint, { method: 'DELETE' });
  },

  showToast(message, type = 'info') {
    let container = document.getElementById('toast-container');
    if (!container) {
      container = document.createElement('div');
      container.id = 'toast-container';
      container.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:8px;';
      document.body.appendChild(container);
    }

    message = this.userMessage(message);
    let displayMessage = message;
    if (typeof I18n !== 'undefined' && I18n.isMyanmar()) {
      if (type === 'error') {
        displayMessage = I18n.translateError(message);
      } else {
        displayMessage = I18n.t(message, I18n.translateError(message));
      }
    }

    const toast = document.createElement('div');
    const bgColors = {
      success: '#15803d',
      error: '#b91c1c',
      warning: '#b45309',
      info: '#0f172a'
    };

    toast.style.cssText = `
      background:${bgColors[type] || '#0f172a'};
      color:#fff;
      padding:10px 16px;
      border-radius:8px;
      font-size:0.875rem;
      box-shadow:0 4px 12px rgba(0,0,0,0.15);
      animation:fadeIn 0.2s ease-in-out;
      display:flex;
      align-items:center;
      gap:8px;
    `;
    toast.textContent = displayMessage;
    container.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transition = 'opacity 0.3s';
      setTimeout(() => toast.remove(), 300);
    }, 3500);
  }
};

window.Auth = {
  logout: () => API.logout()
};

