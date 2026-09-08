const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
for(const zone of ['UTC','America/Los_Angeles','Asia/Tokyo'])test('Yangon formatting and date boundaries with device timezone '+zone,()=>{
 const previous=process.env.TZ;process.env.TZ=zone;
 try{const context=vm.createContext({Date,Intl,console,performance:{now:()=>100},window:{},document:{addEventListener(){}},I18n:{isMyanmar:()=>false}});
 vm.runInContext(fs.readFileSync('src/main/webapp/js/api.js','utf8')+';globalThis.api=API;',context);
 const api=context.api;
 for(const field of ['saleDate','wasteDate','createdAt']){const rendered=api.formatTimestamp('2026-09-08T18:00:00Z');assert.equal(rendered.date,'2026-09-09',field);assert.equal(rendered.time,'00:30',field)}
 assert.equal(api.formatTimestamp('2026-09-09T00:30:00+06:30',true).time,'00:30');
 assert.equal(api.formatTimestamp('2026-09-09T00:30',true).time,'00:30');
 assert.equal(api.formatTimestamp('2026-09-08T18:00:00').time,'00:30');
 api.serverInstant=Date.parse('2026-09-08T17:30:00Z');api.serverTick=100;assert.equal(api.today(),'2026-09-09');
 assert.equal(api.formatTimestamp('2026-09-08T17:29:59Z').date,'2026-09-08');
 vm.runInContext(fs.readFileSync('src/main/webapp/js/reports.js','utf8')+';globalThis.reports=Reports;',context);
 const period=context.reports._dateRangeFor('today');assert.equal(period.startDate,'2026-09-09');assert.equal(period.endDate,'2026-09-09');
 }finally{if(previous===undefined)delete process.env.TZ;else process.env.TZ=previous;}
});
