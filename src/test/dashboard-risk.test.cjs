// Run with: node src/test/dashboard-risk.test.cjs
const {readFileSync} = require('node:fs');
const {runInNewContext} = require('node:vm');
const assert = require('node:assert/strict');
const context = {window: {}, document: {readyState: 'loading', addEventListener() {}, getElementById() {return null;}}, console};
runInNewContext(readFileSync('src/main/webapp/js/dashboard.js','utf8'), context);
const dashboard = context.window.Dashboard;
dashboard.generateDynamicRecommendations = () => {};
dashboard.processPredictionData({items: [
    {foodName:'Generic medium',stock:20,riskLevel:'MEDIUM',riskScore:82,currentDaysRemaining:10},
    {foodName:'Generic high',stock:10,riskLevel:'HIGH',riskScore:78,currentDaysRemaining:1},
    {foodName:'Depleted',stock:0,riskLevel:'HIGH',riskScore:85}
]});
assert.equal(dashboard.data.highRiskFoods.length,1);
assert.equal(dashboard.data.highRiskFoods[0].name,'Generic high');
assert.equal(dashboard.data.highRiskFoods[0].riskPct,78);
assert.equal(dashboard.data.highRiskFoods[0].expiryDays,1);
dashboard.processPredictionData({items: []});
assert.equal(dashboard.data.highRiskFoods.length,0);
console.log('PASS: backend status and score remain authoritative; zero stock and stale rows excluded.');
