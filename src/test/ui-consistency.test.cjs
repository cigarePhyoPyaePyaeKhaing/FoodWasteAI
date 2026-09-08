const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=process.cwd(), web=path.join(root,'src/main/webapp');
const forecast=JSON.parse(fs.readFileSync('target/consistency-forecast-fixture.json','utf8'));
const inventory=forecast.forecastItems.map(i=>({id:i.foodItemId,name:i.name,category:i.category,unit:i.unit,quantity:i.currentStock,remainingQuantity:i.currentStock,totalQuantity:100,pricePerUnit:2000,expiryDate:i.expiryDate,expiryDaysRemaining:i.currentDaysRemaining,redistributionStatus:i.redistributionStatus,status:i.currentDaysRemaining<=3?'NEAR_EXPIRY':'OK'}));
const stats={completedQuantities:['55.5 pcs'],pendingQuantities:['30.0 kg'],confirmedValueSaved:55500,pendingRedistributionValue:60000,pendingDispatchesCount:1,activeCharitiesCount:4};
const dispatches=[{id:1,foodItemId:1,foodItemName:'Completed fixture',quantity:55.5,unit:'pcs',status:'COMPLETED',pickupTime:'2026-09-03T08:19:00'},{id:2,foodItemId:1,foodItemName:'Pending fixture',quantity:30,unit:'kg',status:'PENDING',pickupTime:'2026-09-08T00:35:00'}];
(async()=>{
const browser=await chromium.launch({...(process.env.BROWSER_EXECUTABLE ? {executablePath:process.env.BROWSER_EXECUTABLE} : {channel:'chrome'}),headless:true});
try {
const context=await browser.newContext({timezoneId:'America/Los_Angeles'}),page=await context.newPage();
const errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.route('**/*',async route=>{
const u=new URL(route.request().url());
if(u.hostname!=='foodwaste.test')return route.abort();
let data;
if(u.pathname.startsWith('/api/')) {
if(u.pathname==='/api/prediction')data=forecast;
else if(u.pathname==='/api/inventory')data=u.searchParams.has('expiredReview')?[]:inventory;
else if(u.pathname==='/api/redistribution/stats')data=stats;
else if(u.pathname==='/api/redistribution')data=dispatches;
else if(u.pathname==='/api/redistribution/candidates')data={priorityCandidates:[],redistributionCandidates:[],notEligible:[]};
else data=[];
return route.fulfill({contentType:'application/json',body:JSON.stringify({success:true,data})});
}
const file=path.join(web,u.pathname.endsWith('.html')?'WEB-INF/protected'+u.pathname:u.pathname);
if(!fs.existsSync(file))return route.fulfill({status:404,body:''});
const types={'.html':'text/html','.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'};
return route.fulfill({contentType:types[path.extname(file)]||'application/octet-stream',body:fs.readFileSync(file)});
});
await page.goto('http://foodwaste.test/dashboard.html');await page.waitForFunction(()=>window.Dashboard?.data.predictionData?.forecastContractVersion===2);
assert.equal(await page.evaluate(()=>API.formatTimestamp('2026-09-06T17:58:59').text),'2026-09-07 00:28 (Yangon)');
assert.equal(await page.evaluate(()=>API.formatTimestamp('2026-09-08T00:35:00',true).text),'2026-09-08 00:35 (Yangon)');
assert.equal(await page.locator('#kpi-today-sub').textContent(),'All recorded confirmed waste');
assert.equal(await page.locator('#kpi-predicted-tomorrow').textContent(),forecast.weeklySummary.quantities.join(' • '));

// Test the live controller as well as its rendered controls.
assert.equal(await page.evaluate(()=>Dashboard.data.recommendations.find(r=>r.foodItemId===2).isRedistribution),false);
assert.ok((await page.locator('#dashboard-rec-container').textContent()).includes('Potential Savings'));
await page.evaluate(()=>{let n=0;const original=Dashboard.openDetailsModal;Dashboard.openDetailsModal=function(){window.openCount=++n;return original.call(this);};});
await page.locator('#btn-pred-details').click();assert.equal(await page.evaluate(()=>window.openCount),1);
await page.waitForSelector('#prediction-details-modal.active');assert.equal(await page.locator('.forecast-item').count(),3);
assert.ok((await page.locator('#pred-modal-time').textContent()).includes('2026-09-07 00:30'));
assert.ok((await page.locator('#pred-modal-count').textContent()).includes('3'));
assert.ok((await page.locator('#pred-modal-days').textContent()).includes('7'));
const zeroCard=page.locator('.forecast-item').filter({hasText:'Generic batch 2'});
assert.equal(await zeroCard.locator('.forecast-zero-badge').textContent(),'0 Predicted Waste');
assert.ok((await zeroCard.textContent()).includes('No Surplus'));
assert.ok((await page.locator('.forecast-item').first().textContent()).includes('Estimated Potential Loss'));
assert.ok((await page.locator('.forecast-item').first().textContent()).includes('Recommended Action'));
assert.ok(!/SWI-Prolog|Prolog|Expert Engine|predicate|rule trace/.test(await page.locator('#prediction-details-modal').textContent()));
assert.equal(await page.locator('#pred-modal-total-waste').textContent(),forecast.weeklySummary.quantities.join(' • '));
fs.mkdirSync('target/ui-consistency',{recursive:true});
for(const [size,width,height] of [['desktop',1440,1000],['tablet',768,1024],['mobile',390,844]]) {
await page.setViewportSize({width,height});
for(const lang of ['en','mm']) {
await page.evaluate(lang=>{I18n.setLanguage(lang);Dashboard.data.predictionData.forecastItems[0].name='Generic long inventory name — အစားအစာအမည်ရှည် စမ်းသပ်ချက်';Dashboard.renderDetailsModalContent();},lang);
await page.locator('.forecast-item details').first().evaluate(el=>el.open=true);
for(const theme of ['light','dark']) {
 await page.evaluate(theme=>ThemeManager.applyTheme(theme,true),theme);
 await page.waitForTimeout(350); // Let the existing theme color transition settle for visual QA.
 await page.locator('.forecast-item details').first().evaluate(el=>el.open=true);
 await page.screenshot({path:`target/ui-consistency/forecast-${size}-${lang}-${theme}.png`});
 assert.ok(!/undefined|SWI-Prolog|Expert Engine/.test(await page.locator('#prediction-details-modal').textContent()));
}
await page.evaluate(()=>ThemeManager.applyTheme('light',true));
const geometry=await page.locator('#prediction-details-modal .modal-glass-dialog').evaluate(el=>{const r=el.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,viewport:innerWidth,height:innerHeight};});
assert.ok(geometry.left>=-1&&geometry.right<=width+1,JSON.stringify(geometry));
assert.ok(geometry.top>=-1&&geometry.bottom<=height+1,JSON.stringify(geometry));
const overflow=await page.locator('.forecast-item').first().evaluate(el=>el.scrollWidth>el.clientWidth+1);assert.equal(overflow,false,`${size} ${lang} item overflow`);
}
}
await page.evaluate(()=>I18n.setLanguage('en'));
// Contradictory scores prove the UI follows only the backend category.
for(const lang of ['en','mm']) {
 await page.evaluate(lang=>I18n.setLanguage(lang),lang);
 for(const [level,label] of (lang==='en' ? [['HIGH','HIGH RISK'],['MEDIUM','MEDIUM RISK'],['LOW','LOW RISK'],['OUT_OF_STOCK','OUT OF STOCK']] : [['HIGH','အန္တရာယ်မြင့်'],['MEDIUM','အန္တရာယ်အလယ်အလတ်'],['LOW','အန္တရာယ်နည်း'],['OUT_OF_STOCK','လက်ကျန်မရှိ']])) {
  await page.evaluate(level=>{const i=Dashboard.data.predictionData.forecastItems[0];i.riskLevel=level;i.riskScore=85;i.riskPercentage=85;i.dailyForecast.forEach(d=>{d.riskLevel=level;d.riskScore=73.3;});Dashboard.renderDetailsModalContent();},level);
  const card=page.locator('.forecast-item').first();
  assert.equal(await card.locator('.forecast-item-heading .badge-bubble').textContent(),label);
  assert.ok((await card.textContent()).includes(label));
  assert.ok(!/85%|73.3%|Risk Score|Risk %/.test(await card.textContent()));
 }
 assert.ok(!/%/.test(await page.locator('#high-risk-tbody').textContent()));
 assert.equal(await page.locator('#high-risk-tbody tr').first().locator('td').count(),2);
}
await page.evaluate(()=>I18n.setLanguage('en'));
await page.evaluate(()=>{const item=Dashboard.data.predictionData.forecastItems[0];item.reason='SWI-Prolog Expert Engine';item.recommendedAction='predicate rule trace';Dashboard.renderDetailsModalContent();});
assert.ok(!/SWI-Prolog|Expert Engine|predicate|rule trace/.test(await page.locator('#prediction-details-modal').textContent()));
for(const [state,text] of [['NO_INVENTORY','No active inventory'],['NO_FORECAST','No 7-day evaluation'],['ZERO_FORECAST','No food waste is currently predicted'],['ERROR','Unable to load the 7-day forecast'],['PARTIAL','Some forecast details could not be loaded']]) {
await page.evaluate(({state,forecast})=>{Dashboard.data.forecastError=state==='ERROR';Dashboard.data.predictionData=state==='NO_FORECAST'?null:{...forecast,forecastStatus:state};Dashboard.renderDetailsModalContent();},{state,forecast});
assert.ok((await page.locator('#pred-modal-items-list').textContent()).includes(text),state);
}
await page.goto('http://foodwaste.test/inventory.html');await page.waitForFunction(()=>window.Inventory?.items.length===3);
const milkRow=page.locator('#inventory-tbody tr').filter({hasText:'Generic batch 2'});
assert.equal(await milkRow.getByText(/Priority Donation|Donation Recommended|Consider Redistribution/).count(),0);
await page.evaluate(()=>{Inventory.renderHistoryList([{transactionType:'PURCHASE',quantity:8},{transactionType:'MANUAL_COUNT',quantity:7},{transactionType:'WASTE_ADJUSTMENT',quantity:7}],'kg',false);});
const history=await page.locator('#history-list-container').textContent();assert.ok(history.includes('Physical Stock Count'));assert.ok(history.includes('= 7.00 kg'));assert.ok(history.includes('+ 8.00 kg'));
await page.goto('http://foodwaste.test/redistribution.html');await page.waitForFunction(()=>typeof Redistribution!=='undefined'&&Redistribution.dispatches.length===2);
assert.equal(await page.locator('#kpi-redist-rescued').textContent(),'55.5 pcs');assert.equal(await page.locator('#kpi-redist-money').textContent(),'55,500 MMK');assert.ok((await page.locator('#kpi-redist-impact').textContent()).includes('30.0 kg'));
assert.equal(await page.locator('#redist-tbody tr').filter({hasText:'Completed fixture'}).locator('button').count(),0);
assert.equal(await page.locator('#redist-tbody tr').filter({hasText:'Pending fixture'}).locator('button').count(),2);
assert.equal(await page.locator('#redist-food-id option[value="2"]').count(),0);
for(const name of ['sales','waste','reports','settings']) {await page.goto(`http://foodwaste.test/${name}.html`);await page.waitForTimeout(100);}
for(const name of ['dashboard','inventory','sales','waste','redistribution','reports','settings']) {
 await page.goto(`http://foodwaste.test/${name}.html`);
 for(const width of [1440,768,390]) {await page.setViewportSize({width,height:900});
 for(const lang of ['en','mm']) {await page.evaluate(lang=>I18n.setLanguage(lang),lang);
 assert.ok(!/\b(?:85|50|18)%|Risk Score:|Risk %|အန္တရာယ် %/.test(await page.locator('body').innerText()),name+' risk percentage');
 const button=page.locator('.topbar-right button').last();
 if(name!=='settings') {assert.ok(await button.isVisible(),`${name} core action hidden at ${width} ${lang}`);const rect=await button.boundingBox();assert.ok(rect.x>=0&&rect.x+rect.width<=width+1,`${name} action outside viewport at ${width} ${lang}`);}
 }}
}
await page.evaluate(()=>I18n.setLanguage('en'));
assert.deepEqual(errors,[]);
await page.route('**/api/failure-test',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({success:false,message:'SQLException private detail'})}));
const failure=await page.evaluate(async()=>{let rejected=false;try{await API.get('/api/failure-test');}catch{rejected=true;}return {rejected,text:document.getElementById('toast-container').textContent};});
assert.equal(failure.rejected,true);assert.ok(!failure.text.includes('SQLException'));assert.ok(failure.text.includes('Unable to complete'));
for(const [name,table] of [['inventory','inventory'],['sales','sales'],['waste','waste']]) {
 await page.route(`**/api/${name}`,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({success:false,message:'Unable to load records.'})}));
 await page.goto(`http://foodwaste.test/${name}.html`);await page.locator(`#${table}-tbody`).getByText(/Unable to load/).waitFor();
}
assert.deepEqual(errors,[]);
console.log('PASS: all seven pages load without JS errors; timezone, status KPIs, milk badge/CTA, count history, single-click details, five forecast states, API failure, twelve responsive/language/theme combinations.');
fs.writeFileSync('target/ui-consistency/results.json',JSON.stringify({passed:true,screenshots:12,viewports:['1440x1000','768x1024','390x844'],languages:['en','mm'],browser:'Chrome',browserTimezone:'America/Los_Angeles',pageErrors:errors},null,2));
}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
