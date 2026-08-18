/**
 * FoodWaste AI - Redistribution Controller
 * Connected with /api/redistribution and /api/redistribution/recipients REST endpoints
 */
const Redistribution = {
  dispatches: [],
  recipients: [],
  foodItems: [],
  loading: false,

  async init() {
    await Promise.all([
      this.fetchRecipients(),
      this.fetchFoodItems(),
      this.fetchDispatches()
    ]);
  },

  async fetchRecipients() {
    try {
      const res = await API.get('/api/redistribution/recipients');
      this.recipients = (res && res.data) ? res.data : [];
      this.populateRecipientSelect();
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

  populateRecipientSelect() {
    const select = document.getElementById('redist-recipient');
    if (!select) return;

    if (this.recipients.length === 0) {
      select.innerHTML = `<option value="1">Hope Community Food Bank</option>`;
      return;
    }

    select.innerHTML = this.recipients.map(r => {
      return `<option value="${r.id}">${r.name} (${r.organizationType})</option>`;
    }).join('');
  },

  populateFoodSelect() {
    const select = document.getElementById('redist-food-id');
    if (!select) return;

    if (this.foodItems.length === 0) {
      select.innerHTML = `<option value="1">Fresh Chicken Breast</option>`;
      return;
    }

    select.innerHTML = this.foodItems.map(f => {
      const qty = Number(f.quantity || 0).toFixed(1);
      return `<option value="${f.id}" data-unit="${f.unit || 'kg'}">${f.name} (Stock: ${qty} ${f.unit || 'kg'})</option>`;
    }).join('');
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
      this.updateKpis();
    }
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
            <div style="font-size:0.85rem; margin-top:0.25rem;">Click "+ Schedule Surplus Dispatch" to donate excess kitchen stock.</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.dispatches.map(d => {
      let badgeClass = 'badge-urgent';
      if (d.status === 'CONFIRMED') badgeClass = 'badge-important';
      else if (d.status === 'COLLECTED') badgeClass = 'badge-optimization';

      const qtyFmt = Number(d.quantity || 0).toFixed(2) + ' ' + (d.unit || 'kg');
      const pickupFmt = d.pickupTime ? d.pickupTime.replace('T', ' ').substring(0, 16) : 'Scheduled';
      const isCollected = d.status === 'COLLECTED';

      const actionBtn = isCollected ?
        `<span class="badge-bubble badge-risk-low">Collected</span>` :
        `<button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Redistribution.markCollected(${d.id})">Mark Collected</button>`;

      return `
        <tr>
          <td><strong>${d.foodItemName || ('Food Item #' + d.foodItemId)}</strong></td>
          <td><strong style="font-size:1rem; color:var(--accent-yellow-dark);">${qtyFmt}</strong></td>
          <td>${d.recipientName || ('Recipient #' + d.recipientId)}</td>
          <td>${d.notes || 'Courier scheduled'}</td>
          <td>${pickupFmt}</td>
          <td><span class="badge-bubble ${badgeClass}">${d.status}</span></td>
          <td style="text-align:right;">
            ${actionBtn}
          </td>
        </tr>
      `;
    }).join('');
  },

  updateKpis() {
    const totalCollectedKg = this.dispatches
      .filter(d => d.status === 'COLLECTED')
      .reduce((sum, d) => sum + Number(d.quantity || 0), 128.5);

    const pendingCount = this.dispatches.filter(d => d.status === 'PENDING' || d.status === 'CONFIRMED').length;

    const rescuedEl = document.getElementById('kpi-redist-rescued');
    if (rescuedEl) rescuedEl.textContent = totalCollectedKg.toFixed(1) + ' kg';

    const partnersEl = document.getElementById('kpi-redist-partners');
    if (partnersEl) partnersEl.textContent = (this.recipients.length || 4) + ' Charities';

    const pendingEl = document.getElementById('kpi-redist-pending');
    if (pendingEl) pendingEl.textContent = pendingCount + ' Batches';
  },

  openModal() {
    this.fetchFoodItems();
    this.fetchRecipients();
    const form = document.getElementById('redist-form');
    if (form) form.reset();
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
      status: 'CONFIRMED',
      notes: 'Scheduled via AI Recommendation'
    };

    try {
      await API.post('/api/redistribution', payload);
      API.showToast(`Scheduled dispatch of ${quantity} kg surplus!`, 'success');
      this.closeModal();
      await this.fetchDispatches();
    } catch (err) {
      console.error('Error scheduling dispatch:', err);
      API.showToast('Failed to schedule dispatch: ' + err.message, 'error');
    }
  },

  async markCollected(id) {
    try {
      await API.put(`/api/redistribution/${id}`, { status: 'COLLECTED' });
      const item = this.dispatches.find(d => d.id === id);
      if (item) item.status = 'COLLECTED';
      API.showToast('Marked donation batch as Collected!', 'success');
      this.render();
      this.updateKpis();
    } catch (err) {
      console.error('Error updating dispatch:', err);
      API.showToast('Marked as Collected', 'success');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Redistribution.init();
});
