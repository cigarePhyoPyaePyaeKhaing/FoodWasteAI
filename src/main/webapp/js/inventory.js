/**
 * FoodWaste AI - Inventory Management Controller
 * iOS 26 Glass Bubble Interactive Features
 */
const Inventory = {
  items: [
    { id: 1, name: 'Fresh Chicken Breast', batch: 'CHK-9021', category: 'Poultry', quantity: '50.00 kg', price: '6,500 MMK', expiry: 'Tomorrow (1 Day)', minThreshold: '15.00 kg', status: 'NEAR_EXPIRY', risk: 'HIGH' },
    { id: 2, name: 'Organic Garden Salad Mix', batch: 'SLD-4410', category: 'Produce', quantity: '18.50 kg', price: '4,200 MMK', expiry: 'In 2 Days', minThreshold: '5.00 kg', status: 'NEAR_EXPIRY', risk: 'HIGH' },
    { id: 3, name: 'Atlantic Salmon Fillet', batch: 'SLM-1022', category: 'Seafood', quantity: '12.00 kg', price: '18,000 MMK', expiry: 'In 3 Days', minThreshold: '4.00 kg', status: 'OK', risk: 'MEDIUM' },
    { id: 4, name: 'Premium Jasmine Rice', batch: 'RIC-8831', category: 'Grains', quantity: '120.00 kg', price: '2,800 MMK', expiry: 'In 60 Days', minThreshold: '25.00 kg', status: 'OK', risk: 'LOW' }
  ],

  init() {
    this.bindEvents();
    this.render();
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

  render() {
    const tbody = document.getElementById('inventory-tbody');
    if (!tbody) return;

    const query = (document.getElementById('inv-search-input')?.value || '').toLowerCase();
    const cat = document.getElementById('category-filter')?.value || '';
    const status = document.getElementById('status-filter')?.value || '';

    const filtered = this.items.filter(item => {
      const matchQuery = item.name.toLowerCase().includes(query) || item.category.toLowerCase().includes(query);
      const matchCat = !cat || item.category === cat;
      const matchStatus = !status || item.status === status;
      return matchQuery && matchCat && matchStatus;
    });

    if (filtered.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="8" style="text-align:center; padding:2rem; color:var(--text-muted);">
            No food items matching filter criteria.
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = filtered.map(item => {
      let badgeClass = 'badge-risk-low';
      if (item.status === 'NEAR_EXPIRY') badgeClass = 'badge-risk-high';
      else if (item.status === 'LOW_STOCK') badgeClass = 'badge-risk-medium';

      return `
        <tr>
          <td>
            <strong>${item.name}</strong>
            <div style="font-size:0.75rem; color:var(--text-muted);">Batch #${item.batch}</div>
          </td>
          <td><span class="badge-bubble badge-optimization">${item.category}</span></td>
          <td><strong style="font-size:1rem;">${item.quantity}</strong></td>
          <td>${item.price}</td>
          <td>${item.expiry}</td>
          <td>${item.minThreshold}</td>
          <td><span class="badge-bubble ${badgeClass}">${item.status.replace('_', ' ')}</span></td>
          <td style="text-align:right;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="API.showToast('Item details viewed', 'info')">Edit</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  openModal() {
    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.remove('active');
  },

  saveItem(e) {
    e.preventDefault();
    const name = document.getElementById('new-food-name').value;
    const cat = document.getElementById('new-food-cat').value;
    const qty = document.getElementById('new-food-qty').value;
    const unit = document.getElementById('new-food-unit').value;
    const price = document.getElementById('new-food-price').value;
    const expiry = document.getElementById('new-food-expiry').value;

    this.items.unshift({
      id: Date.now(),
      name: name,
      batch: 'NEW-' + Math.floor(1000 + Math.random() * 9000),
      category: cat,
      quantity: `${qty} ${unit}`,
      price: `${Number(price).toLocaleString()} MMK`,
      expiry: expiry || 'Upcoming',
      minThreshold: '5.00 ' + unit,
      status: 'OK',
      risk: 'LOW'
    });

    this.closeModal();
    this.render();
    API.showToast(`Saved '${name}' into inventory!`, 'success');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Inventory.init();
});
