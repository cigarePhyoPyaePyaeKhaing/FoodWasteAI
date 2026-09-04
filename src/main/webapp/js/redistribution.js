const Redistribution = {
  dispatches: [],
  recipients: [],
  foodItems: [],
  candidatesData: {
    priorityCandidates: [],
    redistributionCandidates: [],
    notEligible: [],
    expiredBlocked: []
  },
  stats: {},
  loading: false,
  submitting: false,
  lastSubmitTime: 0,
  currentSubmissionToken: null,

  escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  },

  async init() {
    // Prevent double-submit via Enter key
    const redistForm = document.getElementById('redist-form');
    if (redistForm) {
      redistForm.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && this.submitting) {
          e.preventDefault();
        }
      });
    }

    // Direct submit button click protection
    const submitBtn = document.getElementById('redist-submit-btn');
    if (submitBtn) {
      submitBtn.addEventListener('click', (e) => {
        if (this.submitting) {
          e.preventDefault();
          e.stopPropagation();
        }
      });
    }

    window.addEventListener('languageChanged', () => {
      this.renderCandidates();
      this.render();
      this.renderRecipientsTable();
      this.populateRecipientSelect();
      this.populateFoodSelect();
      this.updateKpis();
    });

    await Promise.all([
      this.fetchCandidates(),
      this.fetchRecipients(),
      this.fetchFoodItems(),
      this.fetchDispatches(),
      this.fetchStats()
    ]);

    // Check for query parameters passed from Recommendation page
    const params = new URLSearchParams(window.location.search);
    const foodItemId = params.get('foodItemId');
    const qty = params.get('qty');
    if (foodItemId) {
      this.openModal(parseInt(foodItemId, 10), qty ? parseFloat(qty) : null);
    }
  },

  async fetchCandidates() {
    try {
      const res = await API.get('/api/redistribution/candidates');
      if (res && res.data) {
        this.candidatesData = res.data;
      }
    } catch (err) {
      console.warn('Error fetching redistribution candidates:', err);
    } finally {
      this.renderCandidates();
    }
  },

  async fetchRecipients() {
    try {
      const res = await API.get('/api/redistribution/recipients');
      this.recipients = (res && Array.isArray(res.data)) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching recipients:', err);
      this.recipients = [];
    } finally {
      this.populateRecipientSelect();
      this.renderRecipientsTable();
      this.updateKpis();
    }
  },

  async fetchFoodItems() {
    try {
      const res = await API.get('/api/inventory');
      this.foodItems = (res && Array.isArray(res.data)) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching food items for redistribution:', err);
      this.foodItems = [];
    } finally {
      this.populateFoodSelect();
    }
  },

  async fetchDispatches() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/redistribution');
      this.dispatches = (res && Array.isArray(res.data)) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching dispatches:', err);
      this.dispatches = [];
    } finally {
      this.loading = false;
      this.render();
      this.fetchStats();
    }
  },

  async fetchStats() {
    try {
      const res = await API.get('/api/redistribution/stats');
      if (res && res.data) {
        this.stats = res.data;
      }
    } catch (err) {
      console.warn('Error fetching redistribution stats:', err);
    } finally {
      this.updateKpis();
    }
  },

  populateRecipientSelect(selectedRecipientId = null) {
    const select = document.getElementById('redist-recipient');
    if (!select) return;

    if (!this.recipients || this.recipients.length === 0) {
      select.innerHTML = `<option value="" disabled selected>No recipients available</option>`;
      return;
    }

    const defaultPrompt = `<option value="" ${!selectedRecipientId ? 'selected' : ''} disabled>Select redistribution partner...</option>`;
    const options = this.recipients.map(r => {
      const isSel = (selectedRecipientId && Number(r.id) === Number(selectedRecipientId)) ? 'selected' : '';
      return `<option value="${r.id}" ${isSel}>${this.escapeHtml(r.name)} (${this.escapeHtml(r.organizationType)})</option>`;
    }).join('');

    select.innerHTML = defaultPrompt + options;
  },

  populateFoodSelect(selectedFoodId = null) {
    const select = document.getElementById('redist-food-id');
    if (!select) return;

    if (!this.foodItems || this.foodItems.length === 0) {
      select.innerHTML = `<option value="" disabled selected>No food items available</option>`;
      return;
    }

    // Safety filter: exclude zero-stock and expired items from donation selection
    const availableItems = this.foodItems.filter(f => {
      const stock = Number(f.remainingQuantity !== undefined ? f.remainingQuantity : (f.quantity || 0));
      const days = Number(f.daysRemaining !== undefined ? f.daysRemaining : (f.expiryDays !== undefined ? f.expiryDays : 999));
      return stock > 0 && days >= 0 && f.status !== 'EXPIRED';
    });

    if (availableItems.length === 0) {
      select.innerHTML = `<option value="" disabled selected>No safe surplus food items available</option>`;
      return;
    }

    const defaultPrompt = `<option value="" ${!selectedFoodId ? 'selected' : ''} disabled>Select food item...</option>`;
    const options = availableItems.map(f => {
      const qty = Number(f.remainingQuantity !== undefined ? f.remainingQuantity : (f.quantity || 0)).toFixed(1);
      const isSel = (selectedFoodId && Number(f.id) === Number(selectedFoodId)) ? 'selected' : '';
      const nearExpiry = (f.status === 'NEAR_EXPIRY' || (f.daysRemaining !== undefined && f.daysRemaining <= 7)) ? ' ⚠️ Near Expiry' : '';
      return `<option value="${f.id}" data-unit="${this.escapeHtml(f.unit || 'kg')}" ${isSel}>${this.escapeHtml(f.name)} (Stock: ${qty} ${this.escapeHtml(f.unit || 'kg')}${nearExpiry})</option>`;
    }).join('');

    select.innerHTML = defaultPrompt + options;
  },

  renderRecipientsTable() {
    const tbody = document.getElementById('recipients-tbody');
    if (!tbody) return;

    if (!this.recipients || this.recipients.length === 0) {
      tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding:2rem; color:var(--text-muted);">No recipients available</td></tr>`;
      return;
    }

    tbody.innerHTML = this.recipients.map(r => {
      return `
        <tr>
          <td><strong>${this.escapeHtml(r.name)}</strong></td>
          <td><span class="badge-bubble badge-important" style="font-size:0.75rem;">${this.escapeHtml(r.organizationType)}</span></td>
          <td>${this.escapeHtml(r.contactPerson || 'N/A')}</td>
          <td><code>${this.escapeHtml(r.phone || 'N/A')}</code></td>
          <td style="font-size:0.85rem; color:var(--text-muted);">${this.escapeHtml(r.address || '')}</td>
          <td style="text-align:right;">
            <button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Redistribution.openModal(null, null, ${r.id})">+ Dispatch</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  renderLoading() {
    const tbody = document.getElementById('redist-tbody');
    if (!tbody) return;
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
          <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="margin-top:0.5rem; font-weight:600;">Loading redistribution dispatches...</div>
        </td>
      </tr>
    `;
  },

  formatDateTime(isoStr) {
    if (!isoStr) return { date: '-', time: '' };
    const clean = isoStr.replace('T', ' ').trim();
    const parts = clean.split(' ');
    const date = parts[0] || '-';
    let time = parts[1] ? parts[1].substring(0, 5) : '';
    return { date, time };
  },

  formatReason(notes) {
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    if (!notes || notes.trim() === '' || notes === '-') {
      return isMm ? 'သက်တမ်းမကုန်မီ ပိုလျှံလှူဒါန်းမှု' : 'Redistributed before expiry';
    }
    const clean = notes.trim();
    if (isMm && typeof I18n.translateWasteSource === 'function') {
      return I18n.translateWasteSource(clean);
    }
    return clean;
  },

  render() {
    const tbody = document.getElementById('redist-tbody');
    if (!tbody) return;

    if (this.loading) {
      this.renderLoading();
      return;
    }

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    if (!this.dispatches || this.dispatches.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">🤝</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;" data-i18n="redist.empty.title">${isMm ? 'ပိုလျှံသော အစားအစာ မရှိသေးပါ' : 'No surplus available'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${isMm ? '"+ ပိုလျှံစာရင်း အသစ်ထည့်မည်" သို့မဟုတ် AI အကြံပြုချက်များမှတစ်ဆင့် ဆောင်ရွက်ပါ။' : 'Click "+ Schedule Surplus Dispatch" or trigger via AI Recommendation Directives.'}</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.dispatches.map(d => {
      let badgeClass = 'badge-important';
      let statusLabel = d.status || 'PENDING';
      if (d.status === 'COLLECTED' || d.status === 'COMPLETED') {
        badgeClass = 'badge-optimization';
        statusLabel = 'COMPLETED';
      } else if (d.status === 'CANCELLED') {
        badgeClass = 'badge-urgent';
        statusLabel = 'CANCELLED';
      } else if (d.status === 'CONFIRMED' || d.status === 'PENDING') {
        badgeClass = 'badge-important';
        statusLabel = 'PENDING';
      }

      const statusDisplay = typeof I18n !== 'undefined' ? I18n.translateStatus(statusLabel) : statusLabel;
      const rawNotes = typeof I18n !== 'undefined' ? (I18n.getDynamic(d, 'notes') || d.notes) : (d.notes || '');
      const reasonDisplay = this.formatReason(rawNotes);

      const qtyFmt = Number(d.quantity || 0).toFixed(2) + ' ' + (d.unit || 'kg');
      const dt = this.formatDateTime(d.pickupTime);
      const recipientName = d.recipientName || ('Recipient #' + d.recipientId);
      const foodName = d.foodItemName || ('Food Item #' + d.foodItemId);

      let actionBtns = '';
      if (d.status === 'COLLECTED' || d.status === 'COMPLETED') {
        actionBtns = `<span class="badge-bubble badge-risk-low redist-status-icon-badge" title="${isMm ? 'ပို့ဆောင်လှူဒါန်းပြီး' : 'Delivered & Rescued'}">✓</span>`;
      } else if (d.status === 'CANCELLED') {
        actionBtns = `<span class="badge-bubble badge-urgent redist-status-icon-badge" title="${isMm ? 'ပယ်ဖျက်ပြီး' : 'Cancelled'}">✕</span>`;
      } else {
        actionBtns = `
          <div class="redist-action-btn-group">
            <button class="btn-bubble btn-glass-subtle redist-mini-btn" style="color:#ef4444;" onclick="Redistribution.updateStatus(${d.id}, 'CANCELLED')" title="${isMm ? 'ပယ်ဖျက်မည်' : 'Cancel'}">✕</button>
            <button class="btn-bubble btn-yellow redist-mini-btn" onclick="Redistribution.updateStatus(${d.id}, 'COMPLETED')" title="${isMm ? 'ပြီးစီးကြောင်း မှတ်မည်' : 'Mark Completed'}">✓</button>
          </div>
        `;
      }

      return `
        <tr class="redist-row">
          <td class="col-food" data-label="${isMm ? 'အစားအစာ' : 'Food'}">
            <div class="redist-food-box">
              <strong class="redist-food-name" title="${this.escapeHtml(foodName)}">${this.escapeHtml(foodName)}</strong>
            </div>
          </td>
          <td class="col-qty" data-label="${isMm ? 'ပမာဏ' : 'Quantity'}">
            <span class="redist-qty-text">${this.escapeHtml(qtyFmt)}</span>
          </td>
          <td class="col-recipient" data-label="${isMm ? 'လက်ခံမည့်အဖွဲ့' : 'Recipient'}">
            <div class="redist-recipient-text" title="${this.escapeHtml(recipientName)}">
              ${this.escapeHtml(recipientName)}
            </div>
          </td>
          <td class="col-reason" data-label="${isMm ? 'အကြောင်းရင်း' : 'Reason'}">
            <span class="redist-reason-text" title="${this.escapeHtml(reasonDisplay)}">${this.escapeHtml(reasonDisplay)}</span>
          </td>
          <td class="col-date" data-label="${isMm ? 'ရက်စွဲ' : 'Date'}">
            <div class="redist-datetime-box">
              <span class="redist-date-main">${this.escapeHtml(dt.date)}</span>
              ${dt.time ? `<span class="redist-time-sub">${this.escapeHtml(dt.time)}</span>` : ''}
            </div>
          </td>
          <td class="col-status" data-label="${isMm ? 'အခြေအနေ' : 'Status'}">
            <span class="badge-bubble ${badgeClass} redist-status-pill">${this.escapeHtml(statusDisplay)}</span>
          </td>
          <td class="col-action" style="text-align:center;" data-label="${isMm ? 'လုပ်ဆောင်ချက်' : 'Action'}">
            ${actionBtns}
          </td>
        </tr>
      `;
    }).join('');
  },

  renderCandidates() {
    const priorityContainer = document.getElementById('priority-candidates-container');
    const redistContainer = document.getElementById('redist-candidates-container');
    const notEligibleContainer = document.getElementById('not-eligible-container');

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    const data = this.candidatesData || {};
    const priority = data.priorityCandidates || [];
    const candidates = data.redistributionCandidates || [];
    const notEligible = data.notEligible || [];

    // 1. Render Priority Donation Candidates (<= 7 Days to Expiry)
    if (priorityContainer) {
      if (priority.length === 0) {
        priorityContainer.innerHTML = `
          <div style="grid-column:1/-1; text-align:center; padding:1.5rem; color:var(--text-muted); font-size:0.9rem;">
            <span>🛡️</span> ${isMm ? 'လက်ရှိတွင် အရေးပေါ် ဦးစားပေး လှူဒါန်းရန် ပိုလျှံပစ္စည်း မရှိသေးပါ။' : 'No urgent surplus items currently require priority dispatch.'}
          </div>
        `;
      } else {
        priorityContainer.innerHTML = priority.map(item => this.buildCandidateCard(item, true)).join('');
      }
    }

    // 2. Render Recommended Donation Candidates (8–30 Days to Expiry)
    if (redistContainer) {
      if (candidates.length === 0) {
        redistContainer.innerHTML = `
          <div style="grid-column:1/-1; text-align:center; padding:1.5rem; color:var(--text-muted); font-size:0.9rem;">
            <span>📦</span> ${isMm ? '၈ မှ ၃၀ ရက်အတွင်း လှူဒါန်းရန် အကြံပြုထားသော ပိုလျှံပစ္စည်း မရှိသေးပါ။' : 'No surplus candidates detected in the 8–30 day window.'}
          </div>
        `;
      } else {
        redistContainer.innerHTML = candidates.map(item => this.buildCandidateCard(item, false)).join('');
      }
    }

    // 3. Render Ineligible / Not Needed Yet Items (> 30 Days or No Surplus)
    if (notEligibleContainer) {
      if (notEligible.length === 0) {
        notEligibleContainer.innerHTML = `
          <div style="color:var(--text-muted); font-size:0.85rem; padding:0.5rem 0;">
            ${isMm ? 'ကုန်ပစ္စည်းအားလုံးတွင် ပိုလျှံမှု စစ်ဆေးပြီးဖြစ်ပါသည်။' : 'All items evaluated.'}
          </div>
        `;
      } else {
        notEligibleContainer.innerHTML = `
          <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(280px, 1fr)); gap:0.75rem;">
            ${notEligible.map(item => `
              <div style="background:var(--bg-surface-glass-subtle); border:1px solid var(--glass-border-subtle); border-radius:12px; padding:0.75rem 1rem;">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:0.35rem;">
                  <strong style="font-size:0.92rem; color:var(--text-main);">${this.escapeHtml(item.foodName)}</strong>
                  <span class="badge-bubble badge-glass-subtle" style="font-size:0.7rem;">${this.escapeHtml(isMm ? (item.statusLabelMy || item.status) : (item.statusLabelEn || item.status))}</span>
                </div>
                <div style="font-size:0.8rem; color:var(--text-muted);">
                  ${isMm ? 'လက်ကျန်' : 'Stock'}: <strong>${Number(item.stock).toFixed(1)} ${this.escapeHtml(item.unit)}</strong> &bull; 
                  ${isMm ? 'ခန့်မှန်းဝယ်လိုအား' : 'Demand'}: <strong>${Number(item.expectedDemand).toFixed(1)} ${this.escapeHtml(item.unit)}</strong> &bull; 
                  ${isMm ? 'သက်တမ်းကျန်' : 'Expiry'}: <strong>${item.expiryDays} ${isMm ? 'ရက်' : 'd'}</strong>
                </div>
                <div style="font-size:0.75rem; color:var(--text-muted); margin-top:0.35rem; font-style:italic;">
                  ${this.escapeHtml(isMm ? (item.reasonMy || item.reasonEn) : (item.reasonEn || item.reasonMy))}
                </div>
              </div>
            `).join('')}
          </div>
        `;
      }
    }
  },

  buildCandidateCard(item, isPriority) {
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    const badgeStyle = isPriority ? 'background:rgba(220,38,38,0.15); color:#DC2626; border:1px solid rgba(220,38,38,0.3); font-weight:800;' : 'background:rgba(14,165,233,0.15); color:#0284C7; border:1px solid rgba(14,165,233,0.3); font-weight:700;';
    const statusText = isMm ? (item.statusLabelMy || (isPriority ? 'ဦးစားပေး လှူဒါန်းရန်' : 'လှူဒါန်းသင့်')) : (item.statusLabelEn || (isPriority ? 'Priority Donation' : 'Donation Recommended'));
    const reasonText = isMm ? (item.reasonMy || item.reasonEn) : (item.reasonEn || item.reasonMy);
    const actionText = isMm ? (item.suggestedActionMy || item.suggestedActionEn) : (item.suggestedActionEn || item.suggestedActionMy);
    const btnText = isMm ? 'လှူဒါန်းရန် စီစဉ်မည် →' : 'Schedule Dispatch →';

    return `
      <div class="redist-candidate-card" style="background:var(--bg-surface-glass-subtle); border:1px solid var(--glass-border-subtle); border-radius:14px; padding:1.15rem; display:flex; flex-direction:column; justify-content:space-between; gap:0.75rem; transition:all 0.2s ease;">
        <div>
          <div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:0.5rem;">
            <div>
              <div style="display:flex; align-items:center; gap:0.4rem;">
                <span style="font-size:1.1rem;">${isPriority ? '🚨' : '🤝'}</span>
                <strong style="font-size:1.05rem; color:var(--text-main);">${this.escapeHtml(item.foodName)}</strong>
              </div>
              <div style="font-size:0.75rem; color:var(--text-muted); margin-top:0.15rem;">
                ${this.escapeHtml(typeof I18n !== 'undefined' ? I18n.translateFoodCategory(item.category) : item.category)} &bull; ${isMm ? 'သက်တမ်းကျန်' : 'Expires in'}: <strong style="color:var(--text-main);">${item.expiryDays} ${isMm ? 'ရက်' : 'days'}</strong>
              </div>
            </div>
            <span class="badge-bubble" style="${badgeStyle} font-size:0.75rem;">
              ${this.escapeHtml(statusText)}
            </span>
          </div>

          <div style="display:grid; grid-template-columns:repeat(3, 1fr); gap:0.5rem; background:rgba(255,255,255,0.03); border:1px solid var(--glass-border-subtle); border-radius:10px; padding:0.6rem 0.75rem; margin-bottom:0.65rem;">
            <div>
              <div style="font-size:0.7rem; color:var(--text-muted);">${isMm ? 'လက်ကျန်' : 'Stock'}</div>
              <strong style="font-size:0.9rem; color:var(--text-main);">${Number(item.stock).toFixed(1)} ${this.escapeHtml(item.unit)}</strong>
            </div>
            <div>
              <div style="font-size:0.7rem; color:var(--text-muted);">${isMm ? 'ခန့်မှန်းဝယ်လိုအား' : 'Demand'}</div>
              <strong style="font-size:0.9rem; color:var(--text-main);">${Number(item.expectedDemand).toFixed(1)} ${this.escapeHtml(item.unit)}</strong>
            </div>
            <div>
              <div style="font-size:0.7rem; color:var(--text-muted);">${isMm ? 'ခန့်မှန်းပိုလျှံ' : 'Surplus'}</div>
              <strong style="font-size:0.95rem; color:var(--accent-yellow-dark); font-weight:800;">+${Number(item.projectedSurplus).toFixed(1)} ${this.escapeHtml(item.unit)}</strong>
            </div>
          </div>

          <div style="font-size:0.8rem; color:var(--text-muted); line-height:1.4; margin-bottom:0.4rem;">
            <span style="font-weight:600; color:var(--text-main);">${isMm ? 'အကြောင်းရင်း' : 'Reason'}:</span> ${this.escapeHtml(reasonText)}
          </div>

          <div style="font-size:0.8rem; color:var(--text-main); background:rgba(217,119,6,0.08); border-left:3px solid var(--accent-yellow-dark); padding:0.4rem 0.6rem; border-radius:4px;">
            <span style="font-weight:700;">${isMm ? 'အကြံပြုချက်' : 'Directive'}:</span> ${this.escapeHtml(actionText)}
          </div>
        </div>

        <div style="display:flex; justify-content:flex-end; padding-top:0.4rem; border-top:1px solid var(--glass-border-subtle);">
          <button class="btn-bubble ${isPriority ? 'btn-yellow' : 'btn-glass-subtle'} btn-sm-bubble" onclick="Redistribution.openModal(${item.foodItemId}, ${Number(item.projectedSurplus).toFixed(1)})" style="font-weight:700;">
            ${btnText}
          </button>
        </div>
      </div>
    `;
  },

  updateKpis() {
    const rescuedEl = document.getElementById('kpi-redist-rescued');
    const moneyEl = document.getElementById('kpi-redist-money');
    const impactEl = document.getElementById('kpi-redist-impact');
    const partnersEl = document.getElementById('kpi-redist-partners');

    const recipientCount = (this.stats && typeof this.stats.activeCharitiesCount === 'number')
      ? this.stats.activeCharitiesCount
      : (this.recipients ? this.recipients.length : 0);

    if (partnersEl) {
      partnersEl.textContent = `${recipientCount} ${recipientCount === 1 ? 'Partner' : 'Partners'}`;
    }

    const activeDispatches = (this.dispatches || []).filter(d => d.status !== 'CANCELLED');

    if (rescuedEl) {
      if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
        rescuedEl.textContent = I18n.formatUnitAggregate(activeDispatches, d => d.quantity, d => d.unit, '');
      } else {
        const totalQty = activeDispatches.reduce((sum, d) => sum + Number(d.quantity || 0), 0);
        rescuedEl.textContent = totalQty.toFixed(1);
      }
    }

    if (moneyEl) {
      const money = (this.stats && this.stats.estimatedMoneySaved) ? Number(this.stats.estimatedMoneySaved) : 0;
      moneyEl.textContent = money.toLocaleString() + ' MMK';
    }

    if (impactEl) {
      if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
        impactEl.textContent = I18n.formatUnitAggregate(activeDispatches, d => d.quantity, d => d.unit, '');
      } else {
        const totalQty = activeDispatches.reduce((sum, d) => sum + Number(d.quantity || 0), 0);
        impactEl.textContent = totalQty.toFixed(1);
      }
    }
  },

  openModal(preselectedFoodId = null, prefilledQty = null, preselectedRecipientId = null) {
    this.currentSubmissionToken = 'redist_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
    this.submitting = false;
    this.populateFoodSelect(preselectedFoodId);
    this.populateRecipientSelect(preselectedRecipientId);
    const form = document.getElementById('redist-form');
    if (form) {
      form.reset();
      form.style.pointerEvents = '';
    }

    const submitBtn = document.getElementById('redist-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.style.pointerEvents = '';
      submitBtn.style.opacity = '';
      submitBtn.style.cursor = '';
      const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
      submitBtn.textContent = isMm ? 'အတည်ပြုပြီး ပို့ဆောင်ရန် စီစဉ်မည်' : 'Confirm & Schedule Dispatch';
    }

    if (preselectedFoodId) {
      const foodSelect = document.getElementById('redist-food-id');
      if (foodSelect) foodSelect.value = String(preselectedFoodId);
    }
    if (prefilledQty) {
      const qtyInput = document.getElementById('redist-qty');
      if (qtyInput) qtyInput.value = prefilledQty;
    }
    if (preselectedRecipientId) {
      const recSelect = document.getElementById('redist-recipient');
      if (recSelect) recSelect.value = String(preselectedRecipientId);
    }

    const modal = document.getElementById('redist-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    this.submitting = false;
    const modal = document.getElementById('redist-modal');
    if (modal) modal.classList.remove('active');
    const form = document.getElementById('redist-form');
    if (form) form.style.pointerEvents = '';
    const submitBtn = document.getElementById('redist-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.style.pointerEvents = '';
      submitBtn.style.opacity = '';
      submitBtn.style.cursor = '';
      const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();
      submitBtn.textContent = isMm ? 'အတည်ပြုပြီး ပို့ဆောင်ရန် စီစဉ်မည်' : 'Confirm & Schedule Dispatch';
    }
  },

  async saveDispatch(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }

    // 1. Guard against duplicate / rapid concurrent submissions
    if (this.submitting) {
      console.warn('[Redistribution] Submission already in progress. Ignoring duplicate trigger.');
      return;
    }

    const now = Date.now();
    if (now - this.lastSubmitTime < 1500) {
      console.warn('[Redistribution] Rapid submission throttled (<1500ms).');
      return;
    }

    const foodSelect = document.getElementById('redist-food-id');
    const foodItemId = parseInt(foodSelect.value, 10);
    const quantity = parseFloat(document.getElementById('redist-qty').value);
    const recipientSelect = document.getElementById('redist-recipient');
    const recipientId = parseInt(recipientSelect.value, 10);
    const pickupTime = document.getElementById('redist-time').value;

    if (!foodItemId || isNaN(foodItemId)) {
      API.showToast('Please select a valid food item', 'warning');
      return;
    }

    if (!recipientId || isNaN(recipientId)) {
      API.showToast('Please select an available redistribution partner', 'warning');
      return;
    }

    if (isNaN(quantity) || quantity <= 0) {
      API.showToast('Please enter a donation quantity greater than 0', 'warning');
      return;
    }

    // 2. Lock submission state immediately and apply visual loading feedback
    this.submitting = true;
    this.lastSubmitTime = now;

    const submitBtn = document.getElementById('redist-submit-btn');
    const form = document.getElementById('redist-form');
    let origBtnText = '';
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    if (submitBtn) {
      origBtnText = submitBtn.textContent;
      submitBtn.disabled = true;
      submitBtn.style.pointerEvents = 'none';
      submitBtn.style.opacity = '0.65';
      submitBtn.style.cursor = 'not-allowed';
      submitBtn.innerHTML = `
        <span style="display:inline-block; animation: spin 1s linear infinite; margin-right:0.4rem;">⏳</span>
        ${isMm ? 'ပို့ဆောင်ရန် စီစဉ်နေပါသည်...' : 'Scheduling Dispatch...'}
      `;
    }

    if (form) {
      form.style.pointerEvents = 'none';
    }

    const payload = {
      foodItemId,
      recipientId,
      quantity,
      unit: foodSelect.options[foodSelect.selectedIndex]?.getAttribute('data-unit') || 'kg',
      pickupTime: pickupTime || null,
      status: 'PENDING',
      notes: 'Scheduled via Surplus Food Redistribution',
      clientRequestId: this.currentSubmissionToken || ('redist_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9))
    };

    try {
      await API.post('/api/redistribution', payload);
      API.showToast(`Redistribution request created for ${quantity} surplus!`, 'success');
      this.closeModal();
      await this.fetchDispatches();
      await this.fetchStats();
    } catch (err) {
      console.error('Error scheduling dispatch:', err);
      API.showToast('Failed to schedule dispatch: ' + err.message, 'error');
    } finally {
      this.submitting = false;
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.style.pointerEvents = '';
        submitBtn.style.opacity = '';
        submitBtn.style.cursor = '';
        submitBtn.textContent = origBtnText || (isMm ? 'အတည်ပြုပြီး ပို့ဆောင်ရန် စီစဉ်မည်' : 'Confirm & Schedule Dispatch');
      }
      if (form) {
        form.style.pointerEvents = '';
      }
    }
  },

  async updateStatus(id, newStatus) {
    try {
      await API.put(`/api/redistribution/${id}`, { status: newStatus });
      const item = this.dispatches.find(d => d.id === id);
      if (item) item.status = newStatus;
      API.showToast(`Redistribution record updated to ${newStatus}!`, 'success');
      this.render();
      await this.fetchStats();
    } catch (err) {
      console.error('Error updating dispatch status:', err);
      API.showToast('Failed to update dispatch status: ' + err.message, 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Redistribution.init();
});
