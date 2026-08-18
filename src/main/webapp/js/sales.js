/**
 * FoodWaste AI - Sales Entry Controller
 * iOS 26 Glass Bubble Interactive Features
 */
const Sales = {
  sales: [
    { time: 'Today, 18:30', food: 'Fresh Chicken Breast', qty: '28.00 kg', unitPrice: '6,500 MMK', total: '182,000 MMK', customers: 45 },
    { time: 'Today, 18:15', food: 'Organic Garden Salad Mix', qty: '14.00 kg', unitPrice: '4,200 MMK', total: '58,800 MMK', customers: 32 },
    { time: 'Today, 17:45', food: 'Atlantic Salmon Fillet', qty: '8.00 kg', unitPrice: '18,000 MMK', total: '144,000 MMK', customers: 20 },
    { time: 'Today, 17:30', food: 'Premium Jasmine Rice', qty: '25.00 kg', unitPrice: '2,800 MMK', total: '70,000 MMK', customers: 80 }
  ],

  init() {
    this.render();
  },

  render() {
    const tbody = document.getElementById('sales-tbody');
    if (!tbody) return;

    tbody.innerHTML = this.sales.map(s => `
      <tr>
        <td>${s.time}</td>
        <td><strong>${s.food}</strong></td>
        <td><strong>${s.qty}</strong></td>
        <td>${s.unitPrice}</td>
        <td><strong style="color:var(--accent-yellow-dark);">${s.total}</strong></td>
        <td style="text-align:right;"><span class="badge-bubble badge-optimization">${s.customers} Diners</span></td>
      </tr>
    `).join('');
  },

  openModal() {
    const modal = document.getElementById('record-sale-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('record-sale-modal');
    if (modal) modal.classList.remove('active');
  },

  saveSale(e) {
    e.preventDefault();
    const foodSelect = document.getElementById('sale-food-id');
    const foodText = foodSelect.options[foodSelect.selectedIndex].text.split('(')[0].trim();
    const qty = document.getElementById('sale-qty').value;
    const customers = document.getElementById('sale-customers').value;

    const unitPriceMap = {
      '1': 6500,
      '2': 4200,
      '3': 18000,
      '4': 2800
    };
    const price = unitPriceMap[foodSelect.value] || 5000;
    const total = Number(qty) * price;

    this.sales.unshift({
      time: 'Just now',
      food: foodText,
      qty: `${qty} kg`,
      unitPrice: `${price.toLocaleString()} MMK`,
      total: `${total.toLocaleString()} MMK`,
      customers: Number(customers)
    });

    this.closeModal();
    this.render();
    API.showToast(`Recorded sale for ${foodText} (${total.toLocaleString()} MMK)`, 'success');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Sales.init();
});
