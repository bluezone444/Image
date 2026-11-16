// 빠른 점검 (30초 안팎): 고교 프롤로그·바로 시작·모든 탭·장비 강화·경기 중계·공유 카드를 돌며 페이지 오류와 가로 넘침을 찾는다
// 사용: node tools/test/smoke.js [HTML 경로]   (기본: career.html) · 스크린샷은 dist/smoke/
const {chromium}=require('playwright');const path=require('path');const fs=require('fs');
const FILE='file://'+path.resolve(process.argv[2]||path.join(__dirname,'../../career.html'));
const OUT=path.join(__dirname,'../../dist/smoke');fs.mkdirSync(OUT,{recursive:true});
(async()=>{const b=await chromium.launch();const errs=[];const shot=(p,n)=>p.screenshot({path:path.join(OUT,n+'.png')});
const page=async(w,h)=>{const p=await b.newPage({viewport:{width:w,height:h}});p.on('pageerror',e=>errs.push(e.message));p.on('console',m=>{if(m.type()==='error'&&!/ERR_FILE_NOT_FOUND/.test(m.text()))errs.push('console: '+m.text().slice(0,160))});return p;};
const over=async(p,where)=>{if(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth))errs.push('가로 넘침: '+where);};
const closeModals=p=>p.evaluate(()=>document.querySelectorAll('.modal').forEach(m=>m.remove()));
// 1) 고교 프롤로그로 시작
let p=await page(360,780);await p.goto(FILE);await p.waitForTimeout(900);await shot(p,'title');await over(p,'title');
await p.click('#newg');await p.waitForTimeout(400);await p.fill('#nm','점검');await p.click('#startBtn');await p.waitForTimeout(1500);
if(!(await p.evaluate(()=>/고교|고3|학교/.test(document.body.innerText))))errs.push('프롤로그 화면이 안 보여요');await shot(p,'prologue');await p.close();
// 2) 바로 시작(#skiphs) → 모든 탭
for(const [w,h] of [[360,780],[320,640]]){p=await page(w,h);await p.goto('about:blank');await p.goto(FILE+'#skiphs');await p.waitForTimeout(800);
 await p.click('#newg');await p.waitForTimeout(400);await p.fill('#nm','점검');await p.click('#startBtn');await p.waitForTimeout(2600);await closeModals(p);
 for(const t of ['match','player','home','bench','club','world']){await p.click(`.dock button[data-t=${t}]`);await p.waitForTimeout(600);await over(p,`${w}px ${t}`);if(w===360)await shot(p,'tab-'+t);}
 await p.click('.dock button[data-t=player]');await p.waitForTimeout(500);
 if(await p.$('#toForge')){await p.click('#toForge');await p.waitForTimeout(600);await over(p,`${w}px forge`);if(w===360)await shot(p,'forge');
  if(await p.$('#fgGo:not([disabled])')){await p.click('#fgGo');await p.waitForTimeout(2600);}}
 if(w===360){await p.click('.dock button[data-t=player]');await p.waitForTimeout(500);
  if(await p.$('#shareCard')){await p.click('#shareCard');await p.waitForTimeout(2200);if(!(await p.$('.sc-img')))errs.push('공유 카드 이미지가 안 만들어졌어요');await closeModals(p);}
  await p.click('.dock button[data-t=match]');await p.waitForTimeout(500);
  await p.evaluate(()=>{const b=[...document.querySelectorAll('.act:not([disabled])')][0];if(b)b.click();});await p.waitForTimeout(1500);await closeModals(p);
  for(let i=0;i<60&&!(await p.$('#toIv'))&&!(await p.$('#nextBtn'));i++){for(const s of ['.mopt[data-mi="1"]','#evok','#mgok','#psyn','#psyok','.pk-btns button:not([disabled])','.ch-btn:not([disabled])','#ball','#pad','#shoot','.locker','#mg .btn']){const e=await p.$(s);if(e){await e.click({timeout:800}).catch(()=>{});break;}}await p.waitForTimeout(400);}
  await shot(p,'match');}
 await p.close();}
await b.close();
console.log(errs.length?'실패\n- '+errs.join('\n- '):'통과 · 오류 0건 · 스크린샷 dist/smoke/');process.exit(errs.length?1:0);})();
