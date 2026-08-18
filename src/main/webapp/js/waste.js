/**
 * FoodWaste AI - Waste Records Controller
 * iOS 26 Glass Bubble Interactive Features
 */
const Waste = {
  records: [
    { time: 'Yesterday, 22:00', food: 'Fresh Chicken Breast', qty: '4.50 kg', reason: 'OVERPRODUCTION', loss: '29,250 MMK', notes: 'Prepared chicken surplus from dinner service' },
    { time: 'Yesterday, 21:30', food: 'Organic Garden Salad Mix', qty: '3.20 kg', reason: 'EXPIRED', loss: '13,440 MMK', notes: 'Wilted beyond presentation standard' },
    { time: '2 days ago, 22:15', food: 'Artisan Sliced Bread', qty: '6.00 units', reason: 'UNSOLD', loss: '13,200 MMK', notes: 'End of day bakery leftovers' },
    { time: '3 days ago, 14:00', food: 'Atlantic Salmon Fillet', qty: '1.50 kg', reason: 'SPOILED', loss: '27,000 MMK', notes: 'Storage chill drawer malfunction' }
  ],

  init() {
    this.render();
  },

  render() {
    const tbody = document.getElementById('waste-tbody');
    if (!tbody) return;

    tbody.innerHTML = this.records.map(r => {
      let badgeClass = 'badge-urgent';
      if (r.reason === 'UNSOLD') badgeClass = 'badge-important';
      else if (r.reason === 'PREPARATION_WASTE') badgeClass = 'badge-optimization';

      return `
        <tr>
          <td>${r.time}</td>
          <td><strong>${r.food}</strong></td>
          <td><strong style="color:var(--risk-high-text);">${r.qty}</strong></td>
          <td><span class="badge-bubble ${badgeClass}">${r.reason}</span></td>
          <td><strong>${r.loss}</strong></td>
          <td style="color:var(--text-muted); font-size:0.85rem;">${r.notes}</td>
        </tr>
      `;
    }).join('');
  },

  openModal() {
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.remove('active');
  },

  saveWaste(e) {
    e.preventDefault();
    const foodSelect = document.getElementById('waste-food-id');
    const foodText = foodSelect.options[foodSelect.selectedIndex].text;
    const qty = document.getElementById('waste-qty').value;
    const reason = document.getElementById('waste-reason').value;
    const notes = document.getElementById('waste-notes').value || 'Kitchen log entry';

    const costMap = { '1': 6500, '2': 4200, '3': 18000, '6': 2200 };
    const loss = Number(qty) * (costMap[foodSelect.value] || 5000);

    this.records.unshift({
      time: 'Just now',
      food: foodText,
      qty: `${qty} kg`,
      reason: reason,
      loss: `${loss.toLocaleString()} MMK`,
      notes: notes
    });

    this.closeModal();
    this.render();
    API.showToast(`Logged ${qty} kg waste for ${foodText}`, 'warning');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Waste.init();
});
