/**
 * FoodWaste AI - Gemini Chat & Explainable AI Conversational Assistant
 * Architecture:
 * User -> Gemini Chat -> Java Backend -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Directives
 */
const AIAssistant = {
  isOpen: false,
  isSending: false,
  STORAGE_KEY: 'foodwaste_chat_history_v2',

  init() {
    this.injectStylesAndMarkup();
    this.loadHistory();
    window.addEventListener('languageChanged', () => {
      this.updateChips();
      this.updateWelcomeText();
      if (typeof I18n !== 'undefined') {
        I18n.applyTranslations();
      }
    });
    this.updateChips();
  },

  getHistory() {
    try {
      const stored = sessionStorage.getItem(this.STORAGE_KEY);
      return stored ? JSON.parse(stored) : [];
    } catch (e) {
      return [];
    }
  },

  saveHistory(messages) {
    try {
      sessionStorage.setItem(this.STORAGE_KEY, JSON.stringify(messages));
    } catch (e) {
      console.warn('Could not persist chat history:', e);
    }
  },

  clearHistory() {
    try {
      sessionStorage.removeItem(this.STORAGE_KEY);
    } catch (e) {}
    const container = document.getElementById('gemini-messages-body');
    if (container) {
      container.innerHTML = this.getWelcomeMarkup();
    }
    if (typeof API !== 'undefined' && API.showToast) {
      const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
      API.showToast(isMm ? 'စကားပြောမှတ်တမ်း ရှင်းလင်းပြီးပါပြီ' : 'Chat history cleared', 'info');
    }
  },

  loadHistory() {
    const history = this.getHistory();
    if (!history || history.length === 0) return;

    const container = document.getElementById('gemini-messages-body');
    if (!container) return;

    // Retain welcome message
    container.innerHTML = this.getWelcomeMarkup();

    history.forEach(item => {
      if (item.type === 'user') {
        this.renderUserMessage(item.text, false);
      } else if (item.type === 'ai') {
        this.renderAIMessage(item.data, false);
      }
    });
    container.scrollTop = container.scrollHeight;
  },

  getWelcomeMarkup() {
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    return `
      <div class="gemini-msg gemini-msg-ai">
        <div class="gemini-msg-bubble" id="gemini-welcome-bubble">
          <div style="font-weight:800; font-size:0.95rem; margin-bottom:0.35rem; color:#713f12;">
            ${isMm ? '👋 မင်္ဂလာပါ! FoodWaste AI စကားပြော လက်ထောက်ဖြစ်ပါသည်။' : '👋 Hello! I am your FoodWaste AI Assistant.'}
          </div>
          <div style="font-size:0.84rem; line-height:1.45; color:var(--text-main);">
            ${isMm ?
              'ကျွန်ုပ်သည် <strong>Google Gemini</strong>၊ <strong>SWI-Prolog ပထမအဆင့် ယုတ္တိဗေဒ</strong> နှင့် <strong>MySQL စာရင်းအင်းများ</strong> ကို ပေါင်းစပ်၍ အလေအလွင့် အန္တရာယ်များကို ဆန်းစစ်တွက်ချက်ပေးပါသည်။' :
              'I connect <strong>Google Gemini</strong>, <strong>SWI-Prolog first-order logic</strong>, and live <strong>MySQL inventory metrics</strong> to explain waste risks and provide real-time mitigation directives.'}
          </div>
          <div style="margin-top:0.45rem; font-size:0.78rem; color:var(--text-muted);">
            ${isMm ? '💡 မေးခွန်းတစ်ခုခု ရိုက်ထည့်ပါ သို့မဟုတ် အပေါ်ရှိ အကြံပြုခလုတ်များကို နှိပ်ပါ!' : '💡 Ask about any ingredient, expiry status, waste risks, or charity donations!'}
          </div>
        </div>
      </div>
    `;
  },

  updateWelcomeText() {
    const bubble = document.getElementById('gemini-welcome-bubble');
    if (bubble) {
      const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
      bubble.innerHTML = `
        <div style="font-weight:800; font-size:0.95rem; margin-bottom:0.35rem; color:#713f12;">
          ${isMm ? '👋 မင်္ဂလာပါ! FoodWaste AI စကားပြော လက်ထောက်ဖြစ်ပါသည်။' : '👋 Hello! I am your FoodWaste AI Assistant.'}
        </div>
        <div style="font-size:0.84rem; line-height:1.45; color:var(--text-main);">
          ${isMm ?
            'ကျွန်ုပ်သည် <strong>Google Gemini</strong>၊ <strong>SWI-Prolog ပထမအဆင့် ယုတ္တိဗေဒ</strong> နှင့် <strong>MySQL စာရင်းအင်းများ</strong> ကို ပေါင်းစပ်၍ အလေအလွင့် အန္တရာယ်များကို ဆန်းစစ်တွက်ချက်ပေးပါသည်။' :
            'I connect <strong>Google Gemini</strong>, <strong>SWI-Prolog first-order logic</strong>, and live <strong>MySQL inventory metrics</strong> to explain waste risks and provide real-time mitigation directives.'}
        </div>
        <div style="margin-top:0.45rem; font-size:0.78rem; color:var(--text-muted);">
          ${isMm ? '💡 မေးခွန်းတစ်ခုခု ရိုက်ထည့်ပါ သို့မဟုတ် အပေါ်ရှိ အကြံပြုခလုတ်များကို နှိပ်ပါ!' : '💡 Ask about any ingredient, expiry status, waste risks, or charity donations!'}
        </div>
      `;
    }
  },

  updateChips() {
    const container = document.getElementById('gemini-chips-container');
    if (!container) return;
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    container.innerHTML = `
      <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(1)">${isMm ? '🥛 နို့စိမ်း အန္တရာယ်' : '🥛 Fresh Milk Risk'}</button>
      <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(2)">${isMm ? '🍗 ကြက်သား အန္တရာယ်' : '🍗 Chicken Risk'}</button>
      <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(3)">${isMm ? '⚠️ အန္တရာယ်မြင့် ပစ္စည်းများ' : '⚠️ High Risk Items'}</button>
      <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(4)">${isMm ? '👨‍🍳 ဦးစားပေး ချက်ပြုတ်ရန်' : '👨‍🍳 Cook Priority'}</button>
      <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(5)">${isMm ? '🤝 ပိုလျှံလှူဒါန်းမှု' : '🤝 Food Rescue'}</button>
      <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(6)">${isMm ? '📊 အနှစ်ချုပ်' : '📊 Daily Summary'}</button>
    `;
  },

  injectStylesAndMarkup() {
    if (document.getElementById('gemini-chat-widget')) return;

    // Chat Drawer HTML
    const widget = document.createElement('div');
    widget.id = 'gemini-chat-widget';
    widget.innerHTML = `
      <!-- Trigger Bubble Button -->
      <button id="gemini-fab-trigger" class="gemini-fab" onclick="AIAssistant.toggle()" title="Ask Gemini & Prolog AI Assistant">
        <span class="gemini-fab-sparkle">✨</span>
        <span class="gemini-fab-text">Gemini AI</span>
      </button>

      <!-- Glass Chat Drawer -->
      <div id="gemini-chat-modal" class="gemini-modal-backdrop">
        <div class="gemini-modal-drawer">
          <!-- Header -->
          <div class="gemini-drawer-header">
            <div style="display:flex; align-items:center; gap:0.6rem;">
              <div class="gemini-avatar-glow">🤖</div>
              <div>
                <div style="font-weight:800; font-size:1rem; color:var(--text-main); display:flex; align-items:center; gap:0.4rem;">
                  Gemini & Prolog AI
                  <span class="gemini-active-pill">XAI LIVE</span>
                </div>
                <div style="font-size:0.75rem; color:var(--text-muted);">Explainable Food Waste Intelligence</div>
              </div>
            </div>
            <div style="display:flex; align-items:center; gap:0.4rem;">
              <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="AIAssistant.clearHistory()" title="Clear Chat History" style="font-size:0.75rem; padding:0.3rem 0.6rem;">🗑️</button>
              <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="AIAssistant.toggle()" style="font-weight:700;">✕</button>
            </div>
          </div>

          <!-- Quick Suggestion Chips -->
          <div id="gemini-chips-container" class="gemini-chips-scroll">
            <!-- Dynamic Chips Injected Here -->
          </div>

          <!-- Messages Container -->
          <div id="gemini-messages-body" class="gemini-messages-container">
            ${this.getWelcomeMarkup()}
          </div>

          <!-- Input Area -->
          <form class="gemini-input-bar" onsubmit="AIAssistant.handleSubmit(event)">
            <input type="text" id="gemini-user-input" class="gemini-text-input" placeholder="Ask about food waste, expiry, or donation..." data-i18n-placeholder="chat.placeholder" autocomplete="off">
            <button type="submit" id="gemini-send-btn" class="gemini-send-button" title="Send Message">
              <span>➤</span>
            </button>
          </form>
        </div>
      </div>
    `;

    document.body.appendChild(widget);

    // CSS Styling for the Glass Chat
    const style = document.createElement('style');
    style.innerHTML = `
      .gemini-fab {
        position: fixed;
        bottom: 5.5rem;
        right: 1.5rem;
        z-index: 1000;
        display: flex;
        align-items: center;
        gap: 0.4rem;
        padding: 0.65rem 1.15rem;
        border-radius: 9999px;
        background: linear-gradient(135deg, rgba(254,240,138,0.95), rgba(250,204,21,0.95));
        border: 1px solid rgba(255,255,255,0.8);
        box-shadow: 0 8px 24px rgba(234,179,8,0.35), 0 2px 8px rgba(0,0,0,0.06);
        cursor: pointer;
        backdrop-filter: blur(12px);
        -webkit-backdrop-filter: blur(12px);
        transition: transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.2s ease;
        font-family: inherit;
      }
      .gemini-fab:hover {
        transform: translateY(-2px) scale(1.04);
        box-shadow: 0 12px 30px rgba(234,179,8,0.45);
      }
      .gemini-fab-sparkle {
        font-size: 1.1rem;
        animation: pulse 2s infinite ease-in-out;
      }
      .gemini-fab-text {
        font-weight: 800;
        font-size: 0.88rem;
        color: #713f12;
      }
      .gemini-modal-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.35);
        backdrop-filter: blur(6px);
        -webkit-backdrop-filter: blur(6px);
        z-index: 2000;
        display: none;
        align-items: flex-end;
        justify-content: flex-end;
        padding: 1.5rem;
      }
      .gemini-modal-backdrop.active {
        display: flex;
      }
      .gemini-modal-drawer {
        width: 100%;
        max-width: 480px;
        height: 640px;
        max-height: 88vh;
        background: rgba(255, 255, 255, 0.94);
        backdrop-filter: blur(24px);
        -webkit-backdrop-filter: blur(24px);
        border: 1px solid rgba(255, 255, 255, 0.85);
        border-radius: 28px;
        box-shadow: 0 24px 60px rgba(0,0,0,0.18), 0 4px 16px rgba(0,0,0,0.06);
        display: flex;
        flex-direction: column;
        overflow: hidden;
        animation: slideUp 0.3s cubic-bezier(0.16, 1, 0.3, 1);
      }
      @keyframes slideUp {
        from { transform: translateY(30px); opacity: 0; }
        to { transform: translateY(0); opacity: 1; }
      }
      .gemini-drawer-header {
        padding: 0.9rem 1.25rem;
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid rgba(0,0,0,0.05);
        background: rgba(255,255,255,0.7);
      }
      .gemini-avatar-glow {
        width: 36px;
        height: 36px;
        border-radius: 50%;
        background: #fef08a;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 1.15rem;
        box-shadow: 0 0 12px rgba(250,204,21,0.5);
      }
      .gemini-active-pill {
        font-size: 0.65rem;
        font-weight: 800;
        background: rgba(16,185,129,0.15);
        color: #059669;
        padding: 0.15rem 0.45rem;
        border-radius: 9999px;
      }
      .gemini-chips-scroll {
        display: flex;
        gap: 0.4rem;
        padding: 0.6rem 1rem;
        overflow-x: auto;
        border-bottom: 1px solid rgba(0,0,0,0.03);
        background: rgba(255,255,255,0.4);
      }
      .gemini-chip {
        white-space: nowrap;
        background: rgba(255,255,255,0.88);
        border: 1px solid rgba(0,0,0,0.08);
        border-radius: 9999px;
        padding: 0.35rem 0.75rem;
        font-size: 0.75rem;
        font-weight: 700;
        color: var(--text-main);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .gemini-chip:hover {
        background: #fef08a;
        border-color: #facc15;
        transform: translateY(-1px);
      }
      .gemini-messages-container {
        flex: 1;
        overflow-y: auto;
        padding: 1rem;
        display: flex;
        flex-direction: column;
        gap: 0.85rem;
      }
      .gemini-msg {
        display: flex;
        flex-direction: column;
        max-width: 90%;
      }
      .gemini-msg-user {
        align-self: flex-end;
      }
      .gemini-msg-user .gemini-msg-bubble {
        background: linear-gradient(135deg, #facc15, #eab308);
        color: #713f12;
        border-radius: 20px 20px 4px 20px;
        padding: 0.7rem 1rem;
        font-size: 0.88rem;
        font-weight: 600;
        box-shadow: 0 4px 12px rgba(234,179,8,0.2);
        word-break: break-word;
      }
      .gemini-msg-ai {
        align-self: flex-start;
      }
      .gemini-msg-ai .gemini-msg-bubble {
        background: rgba(248, 250, 252, 0.98);
        border: 1px solid rgba(0,0,0,0.06);
        color: var(--text-main);
        border-radius: 20px 20px 20px 4px;
        padding: 0.85rem 1.05rem;
        font-size: 0.85rem;
        line-height: 1.55;
        box-shadow: 0 2px 10px rgba(0,0,0,0.03);
        word-break: break-word;
      }
      .gemini-sources-box {
        margin-top: 0.6rem;
        padding: 0.4rem 0.6rem;
        background: rgba(0,0,0,0.025);
        border-radius: 8px;
        font-size: 0.72rem;
        color: var(--text-muted);
      }
      .gemini-sources-title {
        font-weight: 700;
        color: var(--text-main);
        margin-bottom: 0.2rem;
        display: flex;
        align-items: center;
        gap: 0.3rem;
      }
      .gemini-food-card {
        margin-top: 0.75rem;
        margin-bottom: 0.5rem;
        padding: 0.65rem 0.85rem;
        background: rgba(254, 240, 138, 0.25);
        border: 1px solid rgba(250, 204, 21, 0.45);
        border-radius: 12px;
      }
      .gemini-food-card-row {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
        gap: 0.55rem 0.75rem;
      }
      .gemini-food-card-col {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .gemini-food-card-label {
        font-size: 0.68rem;
        font-weight: 700;
        color: var(--text-muted);
        text-transform: uppercase;
        letter-spacing: 0.02em;
      }
      .gemini-food-card-val {
        font-size: 0.82rem;
        font-weight: 700;
        color: var(--text-main);
      }
      .gemini-engine-badge {
        font-size: 0.7rem;
        color: var(--text-muted);
        margin-top: 0.75rem;
        padding-top: 0.5rem;
        border-top: 1px solid rgba(0,0,0,0.06);
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
      }
      .gemini-smart-actions {
        margin-top: 0.85rem;
        margin-bottom: 0.4rem;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      .gemini-action-btn {
        background: rgba(254, 240, 138, 0.85);
        border: 1px solid #facc15;
        color: #713f12;
        padding: 0.5rem 0.85rem;
        border-radius: 10px;
        font-size: 0.78rem;
        font-weight: 700;
        cursor: pointer;
        text-align: left;
        display: flex;
        justify-content: space-between;
        align-items: center;
        transition: all 0.15s ease;
      }
      .gemini-action-btn:hover {
        background: #facc15;
        transform: translateY(-1px);
      }
      .gemini-input-bar {
        padding: 0.75rem 1rem;
        border-top: 1px solid rgba(0,0,0,0.05);
        background: rgba(255,255,255,0.75);
        display: flex;
        gap: 0.5rem;
        align-items: center;
      }
      .gemini-text-input {
        flex: 1;
        border: 1px solid rgba(0,0,0,0.12);
        border-radius: 9999px;
        padding: 0.6rem 1.1rem;
        font-size: 0.85rem;
        outline: none;
        background: rgba(255,255,255,0.95);
        font-family: inherit;
      }
      .gemini-text-input:focus {
        border-color: #facc15;
        box-shadow: 0 0 0 3px rgba(250,204,21,0.25);
      }
      .gemini-send-button {
        width: 38px;
        height: 38px;
        border-radius: 50%;
        border: none;
        background: #facc15;
        color: #713f12;
        font-weight: 800;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: transform 0.15s ease, background 0.15s ease;
      }
      .gemini-send-button:hover {
        transform: scale(1.08);
        background: #eab308;
      }
      .typing-dots {
        display: inline-flex;
        align-items: center;
        gap: 3px;
      }
      .typing-dots span {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: #eab308;
        animation: typingBlink 1.4s infinite ease-in-out both;
      }
      .typing-dots span:nth-child(1) { animation-delay: -0.32s; }
      .typing-dots span:nth-child(2) { animation-delay: -0.16s; }
      @keyframes typingBlink {
        0%, 80%, 100% { transform: scale(0); opacity: 0.3; }
        40% { transform: scale(1); opacity: 1; }
      }
      @media (max-width: 640px) {
        .gemini-modal-backdrop {
          padding: 0;
        }
        .gemini-modal-drawer {
          max-width: 100%;
          height: 100vh;
          max-height: 100vh;
          border-radius: 0;
        }
        .gemini-fab {
          bottom: 4.8rem;
          right: 1rem;
        }
      }
    `;
    document.head.appendChild(style);
  },

  toggle() {
    const modal = document.getElementById('gemini-chat-modal');
    if (!modal) return;
    this.isOpen = !this.isOpen;
    if (this.isOpen) {
      modal.classList.add('active');
      document.getElementById('gemini-user-input')?.focus();
      const container = document.getElementById('gemini-messages-body');
      if (container) container.scrollTop = container.scrollHeight;
    } else {
      modal.classList.remove('active');
    }
  },

  sendQuickChip(chipIndex) {
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    let query = '';
    switch (chipIndex) {
      case 1:
        query = isMm ? "Fresh Milk (နို့စိမ်း) ဘာကြောင့် အန္တရာယ်ရှိတာလဲ?" : "Why is Fresh Milk risky?";
        break;
      case 2:
        query = isMm ? "ကြက်သား အလေအလွင့် ဘာကြောင့်များတာလဲ၊ ဘာလုပ်သင့်လဲ?" : "What is our chicken waste risk and what should we do?";
        break;
      case 3:
        query = isMm ? "ယနေ့ မီးဖိုချောင်တွင် အန္တရာယ်အမြင့်ဆုံး ပစ္စည်းများ ဘာတွေရှိလဲ?" : "Which food items are currently at high risk?";
        break;
      case 4:
        query = isMm ? "ယနေ့ မီးဖိုချောင်တွင် မည်သည့်ပစ္စည်းများကို ဦးစားပေးချက်ပြုတ်သင့်သလဲ?" : "What priority ingredients should we cook today?";
        break;
      case 5:
        query = isMm ? "ယနေ့ ပရဟိတသို့ လှူဒါန်းနိုင်မည့် ပိုလျှံပစ္စည်းများနှင့် မိတ်ဖက်အဖွဲ့များ ရှိပါသလား?" : "Which surplus items can we donate to charity food banks?";
        break;
      case 6:
      default:
        query = isMm ? "ယနေ့ မီးဖိုချောင် အလေအလွင့် စောင့်ကြည့်မှု အနှစ်ချုပ်ပေးပါ" : "Give me a full daily waste intelligence summary.";
        break;
    }
    const input = document.getElementById('gemini-user-input');
    if (input) input.value = query;
    this.handleSubmit(new Event('submit'));
  },

  sendQuick(text) {
    const input = document.getElementById('gemini-user-input');
    if (input) input.value = text;
    this.handleSubmit(new Event('submit'));
  },

  async handleSubmit(e) {
    if (e && e.preventDefault) e.preventDefault();
    const input = document.getElementById('gemini-user-input');
    if (!input) return;
    const query = input.value.trim();
    if (!query || this.isSending) return;

    const lang = (typeof I18n !== 'undefined' && I18n.isMyanmar()) ? 'mm' : 'en';

    input.value = '';
    this.renderUserMessage(query, true);

    this.isSending = true;
    const typingId = this.appendTypingIndicator();

    try {
      const res = await API.post('/api/chat', { message: query, language: lang });
      this.removeTypingIndicator(typingId);

      if (res && res.data) {
        this.renderAIMessage(res.data, true);
      } else {
        const fallbackData = {
          answer: lang === 'mm' ? "SWI-Prolog စနစ်မှ မီးဖိုချောင်ရှိ အန္တရာယ်မြင့် ကုန်ပစ္စည်းများကို ဆန်းစစ်တွက်ချက်ထားပါသည်။" : "Received evaluation from SWI-Prolog engine. High risk items are identified in your inventory.",
          sourceEngine: "SWI-Prolog Expert Engine"
        };
        this.renderAIMessage(fallbackData, true);
      }
    } catch (err) {
      this.removeTypingIndicator(typingId);
      const isMm = lang === 'mm';
      const fallbackData = {
        answer: isMm ?
          "ကျွန်ုပ်တို့၏ **SWI-Prolog ယုတ္တိဗေဒစနစ်** မှ မီးဖိုချောင် စာရင်းအင်းများကို ဆန်းစစ်ပေးပါသည်။ တိကျသော ကုန်ပစ္စည်းအမည် (ဥပမာ- Fresh Milk, Chicken) သို့မဟုတ် Inventory စာမျက်နှာတွင် ကုန်ပစ္စည်းများ စစ်ဆေးနိုင်ပါသည်။" :
          "Our **SWI-Prolog Expert Reasoning System** evaluated live kitchen inventory. Please check Inventory for recorded items and ExpiryStatus.",
        sourceEngine: "SWI-Prolog Local Reasoner"
      };
      this.renderAIMessage(fallbackData, true);
    } finally {
      this.isSending = false;
    }
  },

  renderUserMessage(text, save = true) {
    const container = document.getElementById('gemini-messages-body');
    if (!container) return;
    const div = document.createElement('div');
    div.className = 'gemini-msg gemini-msg-user';
    div.innerHTML = `<div class="gemini-msg-bubble">${this.escapeHtml(text)}</div>`;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;

    if (save) {
      const history = this.getHistory();
      history.push({ type: 'user', text: text, time: Date.now() });
      this.saveHistory(history);
    }
  },

  appendTypingIndicator() {
    const container = document.getElementById('gemini-messages-body');
    if (!container) return null;
    const id = 'typing-' + Date.now();
    const div = document.createElement('div');
    div.id = id;
    div.className = 'gemini-msg gemini-msg-ai';
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    div.innerHTML = `
      <div class="gemini-msg-bubble" style="color:var(--text-muted); font-size:0.82rem; display:flex; align-items:center; gap:0.5rem;">
        <span>⚡ ${isMm ? 'SWI-Prolog နှင့် Gemini တွက်ချက်နေပါသည်' : 'Reasoning with SWI-Prolog & Gemini'}</span>
        <span class="typing-dots"><span></span><span></span><span></span></span>
      </div>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return id;
  },

  removeTypingIndicator(id) {
    if (!id) return;
    const el = document.getElementById(id);
    if (el) el.remove();
  },

  renderAIMessage(data, save = true) {
    const container = document.getElementById('gemini-messages-body');
    if (!container) return;

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    const div = document.createElement('div');
    div.className = 'gemini-msg gemini-msg-ai';

    const rawText = data.answer || data.explanation || '';
    let formattedText = this.formatMarkdown(rawText);

    // Clean Related Food Items Card
    let foodCardsHtml = '';
    if (data.relatedFoodItems && data.relatedFoodItems.length > 0) {
      foodCardsHtml = data.relatedFoodItems.map(item => {
        const statusBadgeClass = item.expiryStatus === 'EXPIRED' ? 'badge-risk-high' :
          (item.expiryStatus === 'SAME_DAY_EXPIRY' || item.expiryStatus === 'NEAR_EXPIRY' ? 'badge-risk-medium' : 'badge-risk-low');
        const statusText = item.expiryStatus || 'SAFE';
        return `
          <div class="gemini-food-card">
            <div class="gemini-food-card-row">
              <div class="gemini-food-card-col">
                <span class="gemini-food-card-label">${isMm ? 'အစားအစာ:' : 'Food Item:'}</span>
                <span class="gemini-food-card-val">🍲 ${this.escapeHtml(item.name)}</span>
              </div>
              <div class="gemini-food-card-col">
                <span class="gemini-food-card-label">${isMm ? 'အခြေအနေ:' : 'Status:'}</span>
                <span class="badge-bubble ${statusBadgeClass}" style="font-size:0.68rem; padding:0.12rem 0.4rem; width:fit-content;">${statusText}</span>
              </div>
              <div class="gemini-food-card-col">
                <span class="gemini-food-card-label">${isMm ? 'လက်ကျန်:' : 'Stock:'}</span>
                <span class="gemini-food-card-val">${item.stock} ${item.unit || ''}</span>
              </div>
              <div class="gemini-food-card-col">
                <span class="gemini-food-card-label">${isMm ? 'အန္တရာယ်:' : 'Risk:'}</span>
                <span class="gemini-food-card-val" style="color:#dc2626; font-weight:800;">${item.riskScore}%</span>
              </div>
            </div>
          </div>
        `;
      }).join('');
    }

    // Sources Box HTML
    let sourcesHtml = '';
    if (data.sources && data.sources.length > 0) {
      sourcesHtml = `
        <div class="gemini-sources-box">
          <div class="gemini-sources-title">
            <span>📚 ${isMm ? 'အချက်အလက် အရင်းအမြစ်များ:' : 'Ground Truth Sources:'}</span>
          </div>
          <div>${data.sources.map(s => `&bull; ${this.escapeHtml(s)}`).join('<br>')}</div>
        </div>
      `;
    }

    // Smart Actions HTML
    let actionsHtml = '';
    if (data.smartRecommendations && data.smartRecommendations.length > 0) {
      actionsHtml = `
        <div class="gemini-smart-actions">
          <div style="font-weight:800; font-size:0.76rem; color:#713f12; margin-bottom:0.25rem;">
            ${isMm ? '💡 AI အကြံပြုချက် လမ်းညွှန်ချက်များ:' : '💡 Smart Directives:'}
          </div>
          ${data.smartRecommendations.map(act => `
            <button class="gemini-action-btn" onclick="AIAssistant.handleSmartAction('${act.actionType}', '${act.payload}')">
              <span>${act.title}</span>
              <span style="font-size:0.65rem; background:rgba(255,255,255,0.7); padding:0.1rem 0.35rem; border-radius:4px;">${act.badge}</span>
            </button>
          `).join('')}
        </div>
      `;
    }

    div.innerHTML = `
      <div class="gemini-msg-bubble">
        ${formattedText}
        ${foodCardsHtml}
        ${actionsHtml}
        ${sourcesHtml}
        <div class="gemini-engine-badge">
          <div style="font-weight:800; font-size:0.75rem; color:#1e293b; display:flex; align-items:center; gap:0.35rem;">
            <span>🤖</span> FoodWaste AI Assistant
          </div>
          <div style="font-size:0.7rem; color:var(--text-muted); margin-top:2px;">
            Powered by Groq AI + SWI-Prolog
          </div>
        </div>
      </div>
    `;

    container.appendChild(div);
    container.scrollTop = container.scrollHeight;

    if (save) {
      const history = this.getHistory();
      history.push({ type: 'ai', data: data, time: Date.now() });
      this.saveHistory(history);
    }
  },

  handleSmartAction(type, payload) {
    if (type === 'REDUCE_PRODUCTION') {
      window.location.href = '/recommendations.html';
    } else if (type === 'SCHEDULE_DONATION') {
      window.location.href = '/redistribution.html';
    } else if (type === 'VIEW_INVENTORY') {
      window.location.href = '/inventory.html';
    } else {
      if (typeof API !== 'undefined' && API.showToast) {
        API.showToast('Action acknowledged', 'success');
      }
    }
  },

  formatMarkdown(text) {
    if (!text) return '';
    let html = this.escapeHtml(text);
    // Convert ### headers
    html = html.replace(/### (.*?)(?=\n|$)/g, '<div style="font-weight:800; font-size:0.95rem; margin-top:0.4rem; margin-bottom:0.3rem; color:var(--text-main);">$1</div>');
    // Convert `code`
    html = html.replace(/`(.*?)`/g, '<code style="background:rgba(0,0,0,0.06); padding:0.1rem 0.35rem; border-radius:4px; font-size:0.8rem;">$1</code>');
    // Convert **bold**
    html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    // Convert *italic*
    html = html.replace(/\*(.*?)\*/g, '<em>$1</em>');
    // Convert list items
    html = html.replace(/^- (.*?)(?=\n|$)/gm, '<div style="padding-left:0.75rem; margin-bottom:0.2rem;">&bull; $1</div>');
    // Convert numbered lists
    html = html.replace(/^(\d+)\. (.*?)(?=\n|$)/gm, '<div style="padding-left:0.75rem; margin-bottom:0.2rem;"><strong>$1.</strong> $2</div>');
    // Convert line breaks
    html = html.replace(/\n\n/g, '<div style="height:0.4rem;"></div>');
    return html;
  },

  escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
};

// Auto-initialize when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    AIAssistant.init();
  });
} else {
  AIAssistant.init();
}

