// 10시즌 자동 진행 회귀 테스트: 새 선수로 은퇴까지 클릭해 나가며 페이지 오류·이상 수치를 찾는다 (3~6분)
// 사용: node tools/test/regression.js [HTML 경로]   (기본: career.html)
const path=require('path');const FILE='file://'+path.resolve(process.argv[2]||path.join(__dirname,'../../career.html'));
const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch();const ctx=await b.newContext({viewport:{width:390,height:844},reducedMotion:'reduce'});const p=await ctx.newPage();
const errs=[];p.on('pageerror',e=>errs.push(e.message));p.on('console',m=>{if(m.type()==='error'&&!/fonts|ERR_/.test(m.text()))errs.push(m.text())});
await p.goto('about:blank');await p.goto(FILE+'#skiphs');await p.waitForTimeout(500);await p.click('#newg');await p.waitForTimeout(300);
await p.click('[data-nat=jpn]');await p.click('[data-p=MF]');await p.fill('#nm','십년선수');await p.click('#startBtn');await p.waitForTimeout(600);
const get=()=>p.evaluate(()=>{try{return JSON.parse(localStorage.getItem('pitch-career.v1'))}catch{return null}});
const seen={};let lastYear=0,steps=0,stuck=0,lastSig='';const bad=[];
const click=async sel=>{const el=await p.$(sel);if(el){await el.click({timeout:1500}).catch(()=>{});seen[sel]=(seen[sel]||0)+1;return true;}return false;};
while(steps<9000){steps++;
 const s=await get();
 if(steps%300===0)console.log('step',steps,'phase',s&&s.phase,'round',s&&s.round,'season',s&&s.history.length+1);
 if(s&&s.year!==lastYear){lastYear=s.year;const str=JSON.stringify(s);
   const chk=[];const STAT=['sho','pas','dri','def','phy'];for(const k of STAT){if(!(s.stats[k]>=1&&s.stats[k]<=120))chk.push('stat '+k+'='+s.stats[k]);}
   if(!(s.cond>=0&&s.cond<=100))chk.push('cond '+s.cond);if(!(s.salary>0&&Number.isFinite(s.salary)))chk.push('salary '+s.salary);if(!(s.sp>=0))chk.push('sp '+s.sp);if(str.includes('NaN'))chk.push('NaN');
   console.log(`season ${s.history.length+1} year ${s.year} club ${s.club} ovr? sal ${s.salary} pop ${s.pop} trust ${s.trust} sp ${s.sp} cls ${s.cls} captain ${s.captain} nt ${JSON.stringify(s.nt)} hof ${s.hof?JSON.stringify(s.hof):'-'} ${chk.join(',')}`);if(chk.length)bad.push(chk.join(','));}
 if(s&&s.phase==='retired'&&await p.$('#again')){console.log('RETIRED after',s.history.length,'seasons, hof',JSON.stringify(s.hof));break;}
 // 모달 처리
 if(await click('#salKeep'))continue;
 if(await p.$('#evc:not([hidden])')){await click('#evc button:not([disabled])');await p.waitForTimeout(100);await click('#evok');continue;}
 if(await click('#evok')||await click('#mgok')||await click('#psyok')||await click('#ntok')||await click('#lbok')||await click('#hgok')||await click('#spok')||await click('#skok')||await click('#tok'))continue;
 if(await click('.psy-b:not([disabled])')){await p.waitForTimeout(50);await click('#psyn');continue;}
 if(await click('#psyn'))continue;
 if(await click('.nt-b:not([disabled])'))continue;
 if(await click('#lbgo'))continue;
 if(await p.$('#mg')){if(!await click('#shoot')&&!await click('#ball')&&!await click('.locker')&&!await click('#pad')&&!await click('#mg .btn'))await click('#mg button');continue;}
 if(await click('.pk-btns button:not([disabled])')||await click('.ch-btn:not([disabled])')||await click('.so-btns button:not([disabled])'))continue;
 if(await click('#b11Go'))continue;
 if(await click('#awOpen'))continue; if(await click('#awNext'))continue;
 if(await click('#nextBtn'))continue; if(await p.$('#vn:not([hidden]) button:not([disabled])')){await click('#vn button[data-i="0"]:not([disabled])');continue;}
 if(await click('#toIv'))continue;
 if(await click('#stay'))continue; if(await click('#tnSign'))continue; if(await click('#tnQuit'))continue;
 if(await p.$('.appeal[data-k=accept]')){await click('.appeal[data-k=accept]');continue;} if(await click('#sign'))continue;
 if(await p.$('.mopt')&&!(await p.$('.mopt.on')))await click('.mopt[data-mi="1"]');
 if(await p.$('.act:not([disabled])')){const s2=await get();const a=s2&&s2.cond<45?'[data-a=rest]':'.act:not([disabled])';await click(a);continue;}
 const sig=(await p.evaluate(()=>document.body.innerText.slice(0,80)));if(sig===lastSig){stuck++;if(stuck>40){console.log('STUCK at',sig.replace(/\n/g,' '));await p.screenshot({path:'regression-stuck.png'});break;}}else{stuck=0;lastSig=sig;}
 await p.waitForTimeout(150);
}
const s=await get();console.log('final phase',s&&s.phase,'steps',steps,'bad',bad.length?bad:'none');console.log('seen',JSON.stringify(seen));console.log('errors',errs.slice(0,10));await b.close();process.exit(errs.length||bad.length||!(s&&s.phase==='retired')?1:0);})();
