/**
 * FoodWaste AI - Redistribution Controller
 * iOS 26 Glass Bubble Interactive Features
 */
const Redistribution = {
  dispatches: [
    { id: 1, food: 'Fresh Chicken Breast', qty: '15.00 kg', recipient: 'Hope Community Food Bank', contact: 'Daw Khin Win (+95 9 450012345)', time: 'Tomorrow, 16:30', status: 'CONFIRMED' },
    { id: 2, food: 'Artisan Sliced Bread', qty: '12.00 units', recipient: 'City Youth Shelter & Kitchen', contact: 'U Min Naing (+95 9 790098765)', time: 'Today, 21:00', status: 'PENDING' }
  ],

  init() {
    this.render();
  },

  render() {
    const tbody = document.getElementById('redist-tbody');
    if (!tbody) return;

    tbody.innerHTML = this.dispatches.map(d => {
      let badgeClass = 'badge-urgent';
      if (d.status === 'CONFIRMED') badgeClass = 'badge-important';
      else if (d.status === 'COLLECTED') badgeClass = 'badge-optimization';

      return `
        <tr>
          <td><strong>${d.food}</strong></td>
          <td><strong style="font-size:1rem; color:var(--accent-yellow-dark);">${d.qty}</strong></td>
          <td>${d.recipient}</td>
          <td>${d.contact}</td>
          <td>${d.time}</td>
          <td><span class="badge-bubble ${badgeClass}">${d.status}</span></td>
          <td style="text-align:right;">
            <button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Redistribution.markCollected(${d.id})">Mark Collected</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  openModal() {
    const modal = document.getElementById('redist-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('redist-modal');
    if (modal) modal.classList.remove('active');
  },

  saveDispatch(e) {
    e.preventDefault();
    const foodSelect = document.getElementById('redist-food-id');
    const foodText = foodSelect.options[foodSelect.selectedIndex].text.split('(')[0].trim();
    const qty = document.getElementById('redist-qty').value;
    const recipientSelect = document.getElementById('redist-recipient');
    const recipientText = recipientSelect.options[recipientSelect.selectedIndex].text;
    const time = document.getElementById('redist-time').value;

    this.dispatches.unshift({
      id: Date.now(),
      food: foodText,
      qty: `${qty} kg`,
      recipient: recipientText,
      contact: 'Coordinator Assigned',
      time: time || 'Tomorrow, 14:00',
      status: 'CONFIRMED'
    });

    this.closeModal();
    this.render();
    API.showToast(`Scheduled dispatch of ${qty} kg to ${recipientText}!`, 'success');
  },

  markCollected(id) {
    const item = this.dispatches.find(d => d.id === id);
    if (item) {
      item.status = 'COLLECTED';
      this.render();
      API.showToast(`Marked ${item.food} dispatch as Collected!`, 'success');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Redistribution.init();
});
