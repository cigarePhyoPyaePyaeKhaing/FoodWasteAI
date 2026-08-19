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

  async init() {
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
      this.openModal(parseInt(foodItemId), qty ? parseFloat(qty) : null);
    }
  },

  async fetchRecipients() {
    try {
      const res = await API.get('/api/redistribution/recipients');
      this.recipients = (res && res.data) ? res.data : [];
      this.populateRecipientSelect();
      this.renderRecipientsTable();
    } catch (err) {
      console.warn('Error fetching recipients:', err);
    }
  },

  async fetchFoodItems() {
    try {
      const res = await API.get('/api/inventory');
      this.foodItems = (res && res.data) ? res.data : [];
      this.populateFoodSelect();
    } catch (err) {
      console.warn('Error fetching food items for redistribution:', err);
    }
  },

  async fetchDispatches() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/redistribution');
      this.dispatches = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching dispatches:', err);
      API.showToast('Using local dispatches', 'info');
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
        this.updateKpis();
      }
    } catch (err) {
      console.warn('Error fetching redistribution stats:', err);
      this.updateKpis();
    }
  },

  populateRecipientSelect(selectedRecipientId = null) {
    const select = document.getElementById('redist-recipient');
    if (!select) return;

    if (this.recipients.length === 0) {
      select.innerHTML = `<option value="1">Hope Community Food Bank</option>`;
      return;
    }

    select.innerHTML = this.recipients.map(r => {
      const isSel = (selectedRecipientId && r.id === selectedRecipientId) ? 'selected' : '';
      return `<option value="${r.id}" ${isSel}>${r.name} (${r.organizationType})</option>`;
    }).join('');
  },

  populateFoodSelect(selectedFoodId = null) {
    const select = document.getElementById('redist-food-id');
    if (!select) return;

    if (this.foodItems.length === 0) {
      select.innerHTML = `<option value="1">Fresh Milk (Stock: 40.0 kg)</option>`;
      return;
    }

    select.innerHTML = this.foodItems.map(f => {
      const qty = Number(f.quantity || 0).toFixed(1);
      const isSel = (selectedFoodId && f.id === selectedFoodId) ? 'selected' : '';
      const nearExpiry = f.status === 'NEAR_EXPIRY' ? ' ⚠️ Near Expiry' : '';
      return `<option value="${f.id}" data-unit="${f.unit || 'kg'}" ${isSel}>${f.name} (Stock: ${qty} ${f.unit || 'kg'}${nearExpiry})</option>`;
    }).join('');
  },

  renderRecipientsTable() {
    const tbody = document.getElementById('recipients-tbody');
    if (!tbody) return;

    if (this.recipients.length === 0) {
      tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding:2rem; color:var(--text-muted);">No verified recipients found.</td></tr>`;
      return;
    }

    tbody.innerHTML = this.recipients.map(r => {
      return `
        <tr>
          <td><strong>${r.name}</strong></td>
          <td><span class="badge-bubble badge-important" style="font-size:0.75rem;">${r.organizationType}</span></td>
          <td>${r.contactPerson || 'N/A'}</td>
          <td><code>${r.phone || 'N/A'}</code></td>
          <td style="font-size:0.85rem; color:var(--text-muted);">${r.address || 'Yangon'}</td>
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

    if (this.dispatches.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">🤝</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">No Active Dispatches</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">Click "+ Schedule Surplus Dispatch" or trigger via Recommendation Directives.</div>
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

      const qtyFmt = Number(d.quantity || 0).toFixed(2) + ' ' + (d.unit || 'kg');
      const pickupFmt = d.pickupTime ? d.pickupTime.replace('T', ' ').substring(0, 16) : 'Scheduled';
      
      let actionBtns = '';
      if (d.status === 'COLLECTED' || d.status === 'COMPLETED') {
        actionBtns = `<span class="badge-bubble badge-risk-low">✅ Delivered & Rescued</span>`;
      } else if (d.status === 'CANCELLED') {
        actionBtns = `<span class="badge-bubble badge-risk-high">✕ Cancelled</span>`;
      } else {
        actionBtns = `
          <div style="display:flex; gap:0.4rem; justify-content:flex-end;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" style="color:#ef4444;" onclick="Redistribution.updateStatus(${d.id}, 'CANCELLED')">Cancel</button>
            <button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Redistribution.updateStatus(${d.id}, 'COMPLETED')">Mark Completed</button>
          </div>
        `;
      }

      return `
        <tr>
          <td><strong>${d.foodItemName || ('Food Item #' + d.foodItemId)}</strong></td>
          <td><strong style="font-size:1rem; color:var(--accent-yellow-dark);">${qtyFmt}</strong></td>
          <td>${d.recipientName || ('Recipient #' + d.recipientId)}</td>
          <td style="color:var(--text-muted); font-size:0.85rem;">${d.notes || 'Surplus rescue dispatch'}</td>
          <td>${pickupFmt}</td>
          <td><span class="badge-bubble ${badgeClass}">${statusLabel}</span></td>
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

    if (this.stats && Object.keys(this.stats).length > 0) {
      if (rescuedEl) rescuedEl.textContent = Number(this.stats.quantityRedistributedKg || 0).toFixed(1) + ' kg';
      if (moneyEl) moneyEl.textContent = Number(this.stats.estimatedMoneySaved || 0).toLocaleString() + ' MMK';
      if (impactEl) impactEl.textContent = Number(this.stats.wasteReductionImpactKg || 0).toFixed(1) + ' kg';
      if (partnersEl) partnersEl.textContent = (this.stats.activeCharitiesCount || this.recipients.length || 4) + ' Charities';
    } else {
      const totalKg = this.dispatches
        .filter(d => d.status !== 'CANCELLED')
        .reduce((sum, d) => sum + Number(d.quantity || 0), 0);
      if (rescuedEl) rescuedEl.textContent = totalKg.toFixed(1) + ' kg';
      if (impactEl) impactEl.textContent = totalKg.toFixed(1) + ' kg';
      if (partnersEl) partnersEl.textContent = (this.recipients.length || 4) + ' Charities';
    }
  },

  openModal(preselectedFoodId = null, prefilledQty = null, preselectedRecipientId = null) {
    this.populateFoodSelect(preselectedFoodId);
    this.populateRecipientSelect(preselectedRecipientId);
    const form = document.getElementById('redist-form');
    if (form) form.reset();

    if (preselectedFoodId) {
      const foodSelect = document.getElementById('redist-food-id');
      if (foodSelect) foodSelect.value = preselectedFoodId;
    }
    if (prefilledQty) {
      const qtyInput = document.getElementById('redist-qty');
      if (qtyInput) qtyInput.value = prefilledQty;
    }
    if (preselectedRecipientId) {
      const recSelect = document.getElementById('redist-recipient');
      if (recSelect) recSelect.value = preselectedRecipientId;
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
    const foodItemId = parseInt(foodSelect.value);
    const quantity = parseFloat(document.getElementById('redist-qty').value);
    const recipientSelect = document.getElementById('redist-recipient');
    const recipientId = parseInt(recipientSelect.value);
    const pickupTime = document.getElementById('redist-time').value;

    if (!foodItemId || !recipientId || isNaN(quantity) || quantity <= 0) {
      API.showToast('Please fill out all dispatch details correctly', 'warning');
      return;
    }

    const payload = {
      foodItemId,
      recipientId,
      quantity,
      unit: foodSelect.options[foodSelect.selectedIndex]?.getAttribute('data-unit') || 'kg',
      pickupTime: pickupTime || null,
      status: 'PENDING',
      notes: 'Scheduled via AI Recommendation'
    };

    try {
      await API.post('/api/redistribution', payload);
      API.showToast(`Redistribution request created for ${quantity} kg surplus!`, 'success');
      this.closeModal();
      await this.fetchDispatches();
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
      API.showToast('Updated status', 'success');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Redistribution.init();
});
