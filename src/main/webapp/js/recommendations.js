/**
 * FoodWaste AI - Recommendations Controller
 * iOS 26 Glass Bubble Interactive Features
 */
const Recommendations = {
  init() {
    console.log('Recommendations initialized');
  },

  filter(category) {
    const cards = document.querySelectorAll('.rec-card-bubble');
    cards.forEach(card => {
      if (category === 'ALL' || card.getAttribute('data-category') === category) {
        card.style.display = 'flex';
      } else {
        card.style.display = 'none';
      }
    });

    // Update active button state
    const buttons = document.querySelectorAll('.content-body-float button');
    buttons.forEach(btn => {
      if (btn.textContent.toUpperCase().includes(category)) {
        btn.className = 'btn-bubble btn-yellow btn-sm-bubble';
      } else {
        btn.className = 'btn-bubble btn-glass btn-sm-bubble';
      }
    });
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Recommendations.init();
});
