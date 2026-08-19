const Inventory = {
  items: [],
  editingId: null,
  loading: false,

  async init() {
    this.bindEvents();
    await this.fetchItems();

    window.addEventListener('languageChanged', () => {
      this.render();
    });
  },

  bindEvents() {
    const searchInput = document.getElementById('inv-search-input');
    const catFilter = document.getElementById('category-filter');
    const statusFilter = document.getElementById('status-filter');

    if (searchInput) {
      searchInput.addEventListener('input', () => this.render());
    }
    if (catFilter) {
      catFilter.addEventListener('change', () => this.render());
    }
    if (statusFilter) {
      statusFilter.addEventListener('change', () => this.render());
    }
  },

  async fetchItems() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/inventory');
      this.items = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('API fetch fallback:', err);
      API.showToast('Using local inventory view', 'info');
    } finally {
      this.loading = false;
      this.render();
    }
  },

  renderLoading() {
    const tbody = document.getElementById('inventory-tbody');
    if (!tbody) return;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    tbody.innerHTML = `
      <tr>
        <td colspan="8" style="text-align:center; padding:3rem; color:var(--text-muted);">
          <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="margin-top:0.5rem; font-weight:600;">${isMm ? 'ကုန်ပစ္စည်းစာရင်းများကို ရယူနေပါသည်...' : 'Loading fresh inventory records...'}</div>
        </td>
      </tr>
    `;
  },

  render() {
    const tbody = document.getElementById('inventory-tbody');
    if (!tbody) return;

    if (this.loading) {
      this.renderLoading();
      return;
    }

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const query = (document.getElementById('inv-search-input')?.value || '').toLowerCase();
    const cat = document.getElementById('category-filter')?.value || '';
    const status = document.getElementById('status-filter')?.value || '';

    const filtered = this.items.filter(item => {
      const matchQuery = (item.name || '').toLowerCase().includes(query) || (item.category || '').toLowerCase().includes(query);
      const matchCat = !cat || item.category === cat;
      const matchStatus = !status || item.status === status;
      return matchQuery && matchCat && matchStatus;
    });

    if (filtered.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="8" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">📦</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">${typeof I18n !== 'undefined' ? I18n.t('inv.empty.title') : 'No Food Items Found'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${typeof I18n !== 'undefined' ? I18n.t('inv.empty.desc') : 'Try adjusting your search criteria or click "+ Add Food Item" to add new inventory.'}</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = filtered.map(item => {
      let badgeClass = 'badge-risk-low';
      if (item.status === 'NEAR_EXPIRY' || item.status === 'EXPIRED') badgeClass = 'badge-risk-high';
      else if (item.status === 'LOW_STOCK') badgeClass = 'badge-risk-medium';

      const statusText = typeof I18n !== 'undefined' ? I18n.translateStatus(item.status) : (item.status || 'OK');
      const catText = typeof I18n !== 'undefined' ? I18n.translateFoodCategory(item.category) : item.category;
      const editBtnText = typeof I18n !== 'undefined' ? I18n.t('action.edit') : 'Edit';

      const priceFmt = Number(item.pricePerUnit || 0).toLocaleString() + ' MMK';
      const qtyFmt = Number(item.quantity || 0).toFixed(2) + ' ' + (item.unit || 'kg');
      const minFmt = Number(item.minStockThreshold || 0).toFixed(2) + ' ' + (item.unit || 'kg');
      const expiryFmt = item.expiryDate || 'N/A';

      return `
        <tr>
          <td>
            <strong>${item.name}</strong>
            <div style="font-size:0.75rem; color:var(--text-muted);">ID #${item.id}</div>
          </td>
          <td><span class="badge-bubble badge-optimization">${catText}</span></td>
          <td><strong style="font-size:1rem; color:var(--accent-yellow-dark);">${qtyFmt}</strong></td>
          <td>${priceFmt}</td>
          <td><strong>${expiryFmt}</strong></td>
          <td>${minFmt}</td>
          <td><span class="badge-bubble ${badgeClass}">${statusText}</span></td>
          <td style="text-align:right; white-space:nowrap;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="Inventory.openEditModal(${item.id})">${editBtnText}</button>
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" style="color:var(--risk-high-text); margin-left:0.25rem;" onclick="Inventory.deleteItem(${item.id})">🗑️</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  openModal() {
    this.editingId = null;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    document.getElementById('modal-food-title').textContent = isMm ? '+ ကုန်ပစ္စည်းအသစ် ထည့်သွင်းခြင်း' : '+ Add New Food Item';
    document.getElementById('inventory-form').reset();
    document.getElementById('new-food-threshold').value = '5.0';
    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.add('active');
  },

  openEditModal(id) {
    const item = this.items.find(i => i.id === id);
    if (!item) return;

    this.editingId = id;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    document.getElementById('modal-food-title').textContent = isMm ? ('✏️ ကုန်ပစ္စည်း ပြင်ဆင်ခြင်း #' + id) : ('✏️ Edit Food Item #' + id);
    document.getElementById('new-food-name').value = item.name || '';
    document.getElementById('new-food-cat').value = item.category || 'Poultry';
    document.getElementById('new-food-qty').value = item.quantity || '';
    document.getElementById('new-food-unit').value = item.unit || 'kg';
    document.getElementById('new-food-price').value = item.pricePerUnit || '';
    document.getElementById('new-food-expiry').value = item.expiryDate || '';
    document.getElementById('new-food-threshold').value = item.minStockThreshold || '5.0';

    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    this.editingId = null;
    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.remove('active');
  },

  async saveItem(e) {
    e.preventDefault();
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const name = document.getElementById('new-food-name').value.trim();
    const category = document.getElementById('new-food-cat').value;
    const quantity = parseFloat(document.getElementById('new-food-qty').value);
    const unit = document.getElementById('new-food-unit').value;
    const pricePerUnit = parseFloat(document.getElementById('new-food-price').value);
    const expiryDate = document.getElementById('new-food-expiry').value;
    const minStockThreshold = parseFloat(document.getElementById('new-food-threshold').value) || 5.0;

    if (!name || isNaN(quantity) || isNaN(pricePerUnit) || !expiryDate) {
      API.showToast(isMm ? 'လိုအပ်သော အချက်အလက်များကို မှန်ကန်စွာ ဖြည့်သွင်းပါ' : 'Please fill out all required fields correctly', 'warning');
      return;
    }

    const payload = {
      name,
      category,
      quantity,
      unit,
      pricePerUnit,
      expiryDate,
      minStockThreshold
    };

    try {
      if (this.editingId) {
        payload.id = this.editingId;
        await API.put(`/api/inventory/${this.editingId}`, payload);
        API.showToast(isMm ? `'${name}' ကို ပြင်ဆင်သိမ်းဆည်းပြီးပါပြီ!` : `Updated '${name}' in inventory!`, 'success');
      } else {
        await API.post('/api/inventory', payload);
        API.showToast(isMm ? `'${name}' ကို စာရင်းသို့ ထည့်သွင်းပြီးပါပြီ!` : `Added '${name}' to inventory!`, 'success');
      }
      this.closeModal();
      await this.fetchItems();
    } catch (err) {
      console.error('Error saving item:', err);
      API.showToast(isMm ? ('သိမ်းဆည်းမှု မအောင်မြင်ပါ: ' + err.message) : ('Failed to save item: ' + err.message), 'error');
    }
  },

  async deleteItem(id) {
    const item = this.items.find(i => i.id === id);
    const name = item ? item.name : 'this item';
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    if (!confirm(isMm ? `'${name}' ကို ကုန်ပစ္စည်းစာရင်းမှ ဖျက်ရန် သေချာပါသလား?` : `Are you sure you want to delete '${name}' from inventory?`)) {
      return;
    }

    try {
      await API.delete(`/api/inventory/${id}`);
      API.showToast(isMm ? `'${name}' ကို ဖျက်ပြီးပါပြီ` : `Deleted '${name}'`, 'info');
      await this.fetchItems();
    } catch (err) {
      console.error('Error deleting item:', err);
      API.showToast(isMm ? ('ဖျက်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to delete item: ' + err.message), 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Inventory.init();
});
