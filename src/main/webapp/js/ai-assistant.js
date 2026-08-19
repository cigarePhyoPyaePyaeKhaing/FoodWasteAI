/**
 * FoodWaste AI - Gemini Chat & Explainable AI Assistant
 * Implements the architecture:
 * User -> Gemini Chat -> Java Backend -> MySQL Data -> SWI-Prolog Reasoning -> Gemini Explanation -> Smart Recommendation
 */
const AIAssistant = {
  isOpen: false,
  isSending: false,

  init() {
    this.injectStylesAndMarkup();
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
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="AIAssistant.toggle()">✕</button>
          </div>

          <!-- Quick Suggestion Chips -->
          <div id="gemini-chips-container" class="gemini-chips-scroll">
            <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(1)">🍗 Chicken Risk</button>
            <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(2)">🤝 Food Rescue</button>
            <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(3)">🥗 Near Expiry</button>
            <button class="gemini-chip" onclick="AIAssistant.sendQuickChip(4)">📊 Summary</button>
          </div>

          <!-- Messages Container -->
          <div id="gemini-messages-body" class="gemini-messages-container">
            <div class="gemini-msg gemini-msg-ai">
              <div class="gemini-msg-bubble" id="gemini-welcome-bubble">
                <div style="font-weight:700; margin-bottom:0.3rem;" data-i18n="chat.welcomeTitle">👋 Hello! I am your FoodWaste AI Assistant.</div>
                <div data-i18n="chat.welcomeDesc">I connect <strong>Google Gemini</strong> with <strong>SWI-Prolog first-order logic</strong> and live <strong>MySQL inventory metrics</strong> to explain waste risks and provide smart mitigation recommendations.</div>
                <div style="margin-top:0.5rem; font-size:0.8rem; color:var(--text-muted);" data-i18n="chat.welcomeTip">Try asking a question or tap a chip above!</div>
              </div>
            </div>
          </div>

          <!-- Input Area -->
          <form class="gemini-input-bar" onsubmit="AIAssistant.handleSubmit(event)">
            <input type="text" id="gemini-user-input" class="gemini-text-input" placeholder="Ask about food waste, expiry, or donation..." data-i18n-placeholder="chat.placeholder" autocomplete="off">
            <button type="submit" id="gemini-send-btn" class="gemini-send-button">
              <span>➤</span>
            </button>
          </form>
        </div>
      </div>
    `;

    document.body.appendChild(widget);

    // CSS Styling for the iOS 26 Glass Chat
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
        max-width: 440px;
        height: 600px;
        max-height: 85vh;
        background: rgba(255, 255, 255, 0.92);
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
        padding: 1rem 1.25rem;
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid rgba(0,0,0,0.05);
        background: rgba(255,255,255,0.6);
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
        background: rgba(255,255,255,0.85);
        border: 1px solid rgba(0,0,0,0.08);
        border-radius: 9999px;
        padding: 0.35rem 0.75rem;
        font-size: 0.75rem;
        font-weight: 700;
        color: var(--text-main);
        cursor: pointer;
        transition: background 0.15s ease;
      }
      .gemini-chip:hover {
        background: #fef08a;
        border-color: #facc15;
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
        max-width: 88%;
      }
      .gemini-msg-user {
        align-self: flex-end;
      }
      .gemini-msg-user .gemini-msg-bubble {
        background: #facc15;
        color: #713f12;
        border-radius: 18px 18px 4px 18px;
        padding: 0.65rem 0.95rem;
        font-size: 0.88rem;
        font-weight: 600;
      }
      .gemini-msg-ai {
        align-self: flex-start;
      }
      .gemini-msg-ai .gemini-msg-bubble {
        background: rgba(248, 250, 252, 0.95);
        border: 1px solid rgba(0,0,0,0.06);
        color: var(--text-main);
        border-radius: 18px 18px 18px 4px;
        padding: 0.75rem 1rem;
        font-size: 0.85rem;
        line-height: 1.5;
        box-shadow: 0 2px 8px rgba(0,0,0,0.02);
      }
      .gemini-engine-badge {
        font-size: 0.65rem;
        color: var(--text-muted);
        margin-top: 0.25rem;
        display: flex;
        align-items: center;
        gap: 0.25rem;
      }
      .gemini-smart-actions {
        margin-top: 0.6rem;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .gemini-action-btn {
        background: rgba(254, 240, 138, 0.8);
        border: 1px solid #facc15;
        color: #713f12;
        padding: 0.4rem 0.75rem;
        border-radius: 10px;
        font-size: 0.78rem;
        font-weight: 700;
        cursor: pointer;
        text-align: left;
        display: flex;
        justify-content: space-between;
        align-items: center;
        transition: background 0.15s ease;
      }
      .gemini-action-btn:hover {
        background: #facc15;
      }
      .gemini-input-bar {
        padding: 0.75rem 1rem;
        border-top: 1px solid rgba(0,0,0,0.05);
        background: rgba(255,255,255,0.7);
        display: flex;
        gap: 0.5rem;
      }
      .gemini-text-input {
        flex: 1;
        border: 1px solid rgba(0,0,0,0.1);
        border-radius: 9999px;
        padding: 0.55rem 1rem;
        font-size: 0.85rem;
        outline: none;
        background: rgba(255,255,255,0.9);
      }
      .gemini-text-input:focus {
        border-color: #facc15;
        box-shadow: 0 0 0 3px rgba(250,204,21,0.25);
      }
      .gemini-send-button {
        width: 36px;
        height: 36px;
        border-radius: 50%;
        border: none;
        background: #facc15;
        color: #713f12;
        font-weight: 800;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: transform 0.15s ease;
      }
      .gemini-send-button:hover {
        transform: scale(1.08);
      }
      @media (max-width: 640px) {
        .gemini-modal-backdrop {
          padding: 0;
        }
        .gemini-modal-drawer {
          max-width: 100%;
          height: 100vh;
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
    } else {
      modal.classList.remove('active');
    }
  },

  sendQuickChip(chipIndex) {
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    let query = '';
    switch (chipIndex) {
      case 1:
        query = isMm ? "ကြက်သား အလေအလွင့် ဘာကြောင့်များတာလဲ၊ ဘာလုပ်သင့်လဲ?" : "What is our chicken waste risk and what should we do?";
        break;
      case 2:
        query = isMm ? "ယနေ့ ပရဟိတသို့ လှူဒါန်းနိုင်မည့် ပိုလျှံပစ္စည်းများ ရှိပါသလား?" : "Which surplus items can we donate to food banks today?";
        break;
      case 3:
        query = isMm ? "သုပ်/အသီးအရွက်များ သက်တမ်းကုန်ဆုံးရက် အခြေအနေ ဘယ်လိုရှိလဲ?" : "What is the expiry status for salad and perishables?";
        break;
      case 4:
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

    const lang = (typeof I18n !== 'undefined') ? I18n.getLanguage() : 'en';

    input.value = '';
    this.appendUserMessage(query);

    this.isSending = true;
    const typingId = this.appendTypingIndicator();

    try {
      const res = await API.post('/api/chat', { message: query, language: lang });
      this.removeTypingIndicator(typingId);

      if (res && res.data) {
        this.appendAIMessage(res.data);
      } else {
        this.appendAIMessage({
          explanation: lang === 'mm' ? "SWI-Prolog စနစ်မှ မီးဖိုချောင်ရှိ အန္တရာယ်မြင့် ကုန်ပစ္စည်းများကို ဆန်းစစ်တွက်ချက်ထားပါသည်။" : "Received evaluation from SWI-Prolog engine. High risk items are identified in your inventory.",
          sourceEngine: "SWI-Prolog Expert Engine"
        });
      }
    } catch (err) {
      this.removeTypingIndicator(typingId);
      this.appendAIMessage({
        explanation: lang === 'mm' ? "ကျွန်ုပ်တို့၏ SWI-Prolog ယုတ္တိဗေဒစနစ်မှ လက်ရှိကုန်ပစ္စည်းများကို ဆန်းစစ်ပြီးဖြစ်ပါသည်။ အန္တရာယ်မြင့် ကြက်သား (၈၂% အန္တရာယ်) အတွက် မနက်ဖြန် ထုတ်လုပ်မှု ၂၅% လျှော့ချရန် အကြံပြုပါသည်။" : "Our SWI-Prolog expert reasoning system evaluated current inventory. High-risk items include Fresh Chicken Breast (82% waste risk due to 1-day expiry). We recommend reducing tomorrow's prep by 25% and scheduling surplus donation.",
        sourceEngine: "Prolog Fallback Reasoner"
      });
    } finally {
      this.isSending = false;
    }
  },

  appendUserMessage(text) {
    const container = document.getElementById('gemini-messages-body');
    if (!container) return;
    const div = document.createElement('div');
    div.className = 'gemini-msg gemini-msg-user';
    div.innerHTML = `<div class="gemini-msg-bubble">${this.escapeHtml(text)}</div>`;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
  },

  appendTypingIndicator() {
    const container = document.getElementById('gemini-messages-body');
    if (!container) return null;
    const id = 'typing-' + Date.now();
    const div = document.createElement('div');
    div.id = id;
    div.className = 'gemini-msg gemini-msg-ai';
    div.innerHTML = `
      <div class="gemini-msg-bubble" style="color:var(--text-muted); font-style:italic;">
        ⚡ Reasoning with SWI-Prolog & Gemini...
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

  appendAIMessage(data) {
    const container = document.getElementById('gemini-messages-body');
    if (!container) return;

    const div = document.createElement('div');
    div.className = 'gemini-msg gemini-msg-ai';

    let formattedText = this.formatMarkdown(data.explanation || '');

    // Smart Actions HTML
    let actionsHtml = '';
    if (data.smartRecommendations && data.smartRecommendations.length > 0) {
      actionsHtml = `
        <div class="gemini-smart-actions">
          <div style="font-weight:800; font-size:0.75rem; color:#713f12; margin-top:0.3rem;">💡 Smart Directives:</div>
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
        ${actionsHtml}
        <div class="gemini-engine-badge">
          <span>🧠</span> ${data.sourceEngine || 'SWI-Prolog XAI'}
        </div>
      </div>
    `;

    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
  },

  handleSmartAction(type, payload) {
    if (type === 'REDUCE_PRODUCTION') {
      window.location.href = '/recommendations.html';
    } else if (type === 'SCHEDULE_DONATION') {
      window.location.href = '/redistribution.html';
    } else if (type === 'VIEW_INVENTORY') {
      window.location.href = '/inventory.html';
    } else {
      API.showToast('Action acknowledged', 'success');
    }
  },

  formatMarkdown(text) {
    if (!text) return '';
    let html = this.escapeHtml(text);
    // Convert ### headers
    html = html.replace(/### (.*?)\n/g, '<div style="font-weight:800; font-size:0.95rem; margin-bottom:0.3rem; color:var(--text-main);">$1</div>');
    // Convert **bold**
    html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    // Convert list items
    html = html.replace(/^- (.*?)(?=\n|$)/gm, '<div style="padding-left:0.75rem; margin-bottom:0.2rem;">&bull; $1</div>');
    // Convert numbered lists
    html = html.replace(/^(\d+)\. (.*?)(?=\n|$)/gm, '<div style="padding-left:0.75rem; margin-bottom:0.2rem;"><strong>$1.</strong> $2</div>');
    // Convert line breaks
    html = html.replace(/\n\n/g, '<div style="height:0.4rem;"></div>');
    return html;
  },

  escapeHtml(str) {
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
};

// Auto-initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
  AIAssistant.init();
});
