/**
 * 유치원알리미 Open API → 구글시트 + 휴대폰 웹 화면
 *
 * 1) 구글시트 새로 만들기 → 확장 프로그램 → Apps Script
 * 2) 이 파일 내용을 Code.gs에 붙여넣고 API_KEY에 인증키 입력
 * 3) 파일 추가(+) → HTML → 이름 "index" → index.html 내용 붙여넣기
 * 4) updateSheet 실행 → 시트에 유치원 목록 정리
 * 5) 배포 → 새 배포 → 웹 앱 → 휴대폰에서 링크 열기
 */

const API_KEY = '여기에_유치원알리미_인증키';
const API_URL = 'https://e-childschoolinfo.moe.go.kr/api/notice/basicInfo2.do';

const REGIONS = [
  { name: '수지구', sido: '41', sgg: '41465' },
  { name: '분당구', sido: '41', sgg: '41135' },
  { name: '기흥구', sido: '41', sgg: '41463' }
];

const COLUMNS = [
  ['지역', 'region'], ['유치원명', 'kindername'], ['설립유형', 'establish'], ['주소', 'addr'],
  ['전화', 'telno'], ['홈페이지', 'hpaddr'], ['운영시간', 'opertime'],
  ['만3세 학급', 'clcnt3'], ['만4세 학급', 'clcnt4'], ['만5세 학급', 'clcnt5'], ['혼합 학급', 'mixclcnt'],
  ['만3세 정원', 'ag3fpcnt'], ['만4세 정원', 'ag4fpcnt'], ['만5세 정원', 'ag5fpcnt'], ['혼합 정원', 'mixfpcnt'],
  ['만3세 현원', 'ppcnt3'], ['만4세 현원', 'ppcnt4'], ['만5세 현원', 'ppcnt5'], ['혼합 현원', 'mixppcnt'],
  ['공시차수', 'pbnttmng']
];

function fetchRegion_(r) {
  const cache = CacheService.getScriptCache();
  const cacheKey = 'kg_' + r.sgg;
  const hit = cache.get(cacheKey);
  if (hit) return JSON.parse(hit);

  const url = API_URL + '?key=' + encodeURIComponent(API_KEY) + '&sidoCode=' + r.sido + '&sggCode=' + r.sgg;
  const res = UrlFetchApp.fetch(url, { muteHttpExceptions: true });
  if (res.getResponseCode() !== 200) throw new Error(r.name + ' HTTP ' + res.getResponseCode());
  const json = JSON.parse(res.getContentText());
  const items = json.kinderInfo || [];
  if (!items.length && json.status && String(json.status).toUpperCase() !== 'SUCCESS') {
    throw new Error(r.name + ': ' + json.status + ' (인증키를 확인하세요)');
  }
  try { cache.put(cacheKey, JSON.stringify(items), 6 * 60 * 60); } catch (e) { /* 100KB 초과 시 캐시 생략 */ }
  return items;
}

/** 웹 화면(index.html)에서 호출 */
function getData() {
  return JSON.stringify({
    updated: Utilities.formatDate(new Date(), 'Asia/Seoul', 'yyyy-MM-dd HH:mm'),
    regions: REGIONS.map(function (r) { return { name: r.name, items: fetchRegion_(r) }; })
  });
}

/** 시트에 유치원 목록 정리 */
function updateSheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName('유치원') || ss.insertSheet('유치원');
  const rows = [];
  REGIONS.forEach(function (r) {
    fetchRegion_(r).forEach(function (k) {
      k.region = r.name;
      rows.push(COLUMNS.map(function (c) { return k[c[1]] == null ? '' : k[c[1]]; }));
    });
  });
  sheet.clearContents();
  sheet.getRange(1, 1, 1, COLUMNS.length).setValues([COLUMNS.map(function (c) { return c[0]; })]).setFontWeight('bold');
  if (rows.length) sheet.getRange(2, 1, rows.length, COLUMNS.length).setValues(rows);
  sheet.setFrozenRows(1);
  sheet.autoResizeColumns(1, 4);
}

function doGet() {
  return HtmlService.createHtmlOutputFromFile('index')
    .setTitle('동천동 유치원 찾기')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}
