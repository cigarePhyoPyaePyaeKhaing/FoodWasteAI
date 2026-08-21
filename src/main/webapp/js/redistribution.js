/**
 * FoodWaste AI - Surplus Redistribution Controller
 * Connected with /api/redistribution and /api/redistribution/recipients REST endpoints
 * Complete Workflow: HIGH Risk Item -> Recommendation -> Redistribution Request -> Recipient Selection -> Redistribution Record
 */
const Redistribution = {
  dispatches: [],
  recipients: [],
  foodItems: [],
  stats: {},
  loading: false,

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
    window.addEventListener('languageChanged', () => {
      this.render();
      this.renderRecipientsTable();
      this.populateRecipientSelect();
      this.populateFoodSelect();
      this.updateKpis();
    });

    await Promise.all([
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

    const defaultPrompt = `<option value="" ${!selectedRecipientId ? 'selected' : ''} disabled>Select recipient charity...</option>`;
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

    const defaultPrompt = `<option value="" ${!selectedFoodId ? 'selected' : ''} disabled>Select food item...</option>`;
    const options = this.foodItems.map(f => {
      const qty = Number(f.quantity || 0).toFixed(1);
      const isSel = (selectedFoodId && Number(f.id) === Number(selectedFoodId)) ? 'selected' : '';
      const nearExpiry = f.status === 'NEAR_EXPIRY' ? ' ⚠️ Near Expiry' : '';
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
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;" data-i18n="redist.emptyTitle">${isMm ? 'ပိုလျှံသော အစားအစာ မရှိသေးပါ' : 'No surplus available'}</div>
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
      const notes = typeof I18n !== 'undefined' ? (I18n.getDynamic(d, 'notes') || d.notes) : (d.notes || 'Surplus rescue dispatch');

      const qtyFmt = Number(d.quantity || 0).toFixed(2) + ' ' + (d.unit || 'kg');
      const pickupFmt = d.pickupTime ? d.pickupTime.replace('T', ' ').substring(0, 16) : (isMm ? 'သတ်မှတ်ဆဲ' : 'Scheduled');

      let actionBtns = '';
      if (d.status === 'COLLECTED' || d.status === 'COMPLETED') {
        actionBtns = `<span class="badge-bubble badge-risk-low">${isMm ? '✅ ပို့ဆောင်လှူဒါန်းပြီး' : '✅ Delivered & Rescued'}</span>`;
      } else if (d.status === 'CANCELLED') {
        actionBtns = `<span class="badge-bubble badge-risk-high">${isMm ? '✕ ပယ်ဖျက်ပြီး' : '✕ Cancelled'}</span>`;
      } else {
        actionBtns = `
          <div style="display:flex; gap:0.4rem; justify-content:flex-end;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" style="color:#ef4444;" onclick="Redistribution.updateStatus(${d.id}, 'CANCELLED')">${isMm ? 'ပယ်ဖျက်မည်' : 'Cancel'}</button>
            <button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Redistribution.updateStatus(${d.id}, 'COMPLETED')">${isMm ? 'ပြီးစီးကြောင်း မှတ်မည်' : 'Mark Completed'}</button>
          </div>
        `;
      }

      return `
        <tr>
          <td><strong>${this.escapeHtml(d.foodItemName || ('Food Item #' + d.foodItemId))}</strong></td>
          <td><strong style="font-size:1rem; color:var(--accent-yellow-dark);">${this.escapeHtml(qtyFmt)}</strong></td>
          <td>${this.escapeHtml(d.recipientName || ('Recipient #' + d.recipientId))}</td>
          <td style="color:var(--text-muted); font-size:0.85rem;">${this.escapeHtml(notes || (isMm ? 'ပိုလျှံအစားအစာ လှူဒါန်းမှု' : 'Surplus rescue dispatch'))}</td>
          <td>${this.escapeHtml(pickupFmt)}</td>
          <td><span class="badge-bubble ${badgeClass}">${this.escapeHtml(statusDisplay)}</span></td>
          <td style="text-align:right;">
            ${actionBtns}
          </td>
        </tr>
      `;
    }).join('');
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
      partnersEl.textContent = `${recipientCount} ${recipientCount === 1 ? 'Charity' : 'Charities'}`;
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
    this.populateFoodSelect(preselectedFoodId);
    this.populateRecipientSelect(preselectedRecipientId);
    const form = document.getElementById('redist-form');
    if (form) form.reset();

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
    const modal = document.getElementById('redist-modal');
    if (modal) modal.classList.remove('active');
  },

  async saveDispatch(e) {
    e.preventDefault();
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
      API.showToast('Please select an available recipient charity', 'warning');
      return;
    }

    if (isNaN(quantity) || quantity <= 0) {
      API.showToast('Please enter a donation quantity greater than 0', 'warning');
      return;
    }

    const payload = {
      foodItemId,
      recipientId,
      quantity,
      unit: foodSelect.options[foodSelect.selectedIndex]?.getAttribute('data-unit') || 'kg',
      pickupTime: pickupTime || null,
      status: 'PENDING',
      notes: 'Scheduled via Surplus Food Redistribution'
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
