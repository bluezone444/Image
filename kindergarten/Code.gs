/**
 * 유치원알리미 Open API → 구글시트 + 휴대폰 웹앱 (동천동 반경 5km)
 *
 * 1) 구글시트 새로 만들기 → 확장 프로그램 → Apps Script
 * 2) 이 파일 내용을 Code.gs에 붙여넣고 API_KEY에 인증키 입력
 * 3) 파일 추가(+) → HTML → 이름 "index" → index.html 내용 붙여넣기
 * 4) 함수 선택 → updateSheet 실행 (처음 한 번: 권한 허용, 주소→좌표 변환에 1~2분)
 * 5) 배포 → 새 배포 → 유형: 웹 앱 → 액세스: 나만 → 배포 → 링크를 휴대폰에서 열기
 */

const API_KEY = '여기에_유치원알리미_인증키';
const API_BASE = 'https://e-childschoolinfo.moe.go.kr/api/notice/';

// 기본현황에 더해 붙여 줄 정보 (유치원알리미 Open API 목록에서)
const EXTRAS = [
  { field: '_bus', endpoint: 'schoolBus' },            // 통학차량현황
  { field: '_after', endpoint: 'afterSchoolPresent' }, // 방과후 과정 편성 운영 현황
  { field: '_meal', endpoint: 'schoolMeal' }           // 급식운영현황
];

// 기준점: 용인시 수지구 동천동 행정복지센터 부근
const HOME = { lat: 37.3369, lng: 127.0866 };
const RADIUS_KM = 5;

// 동천동 반경 5km가 걸치는 구
const REGIONS = [
  { name: '수지구', sido: '41', sgg: '41465' },
  { name: '분당구', sido: '41', sgg: '41135' },
  { name: '기흥구', sido: '41', sgg: '41463' }
];

const COLUMNS = [
  ['거리(km)', '_dist'], ['지역', 'region'], ['유치원명', 'kindername'], ['설립유형', 'establish'], ['주소', 'addr'],
  ['전화', 'telno'], ['홈페이지', 'hpaddr'], ['운영시간', 'opertime'],
  ['만3세 학급', 'clcnt3'], ['만4세 학급', 'clcnt4'], ['만5세 학급', 'clcnt5'], ['혼합 학급', 'mixclcnt'],
  ['만3세 정원', 'ag3fpcnt'], ['만4세 정원', 'ag4fpcnt'], ['만5세 정원', 'ag5fpcnt'], ['혼합 정원', 'mixfpcnt'],
  ['만3세 현원', 'ppcnt3'], ['만4세 현원', 'ppcnt4'], ['만5세 현원', 'ppcnt5'], ['혼합 현원', 'mixppcnt'],
  ['2027 예상여석(만4세)', '_vac'], ['통학차량', '_busText'], ['공시차수', 'pbnttmng']
];

function fetchRegion_(r, endpoint) {
  endpoint = endpoint || 'basicInfo2';
  const cache = CacheService.getScriptCache();
  const cacheKey = 'kg_' + endpoint + '_' + r.sgg;
  const hit = cache.get(cacheKey);
  if (hit) return JSON.parse(hit);

  const url = API_BASE + endpoint + '.do?key=' + encodeURIComponent(API_KEY) + '&sidoCode=' + r.sido + '&sggCode=' + r.sgg;
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

function km_(a, b) {
  const R = 6371, toR = Math.PI / 180;
  const dLat = (b.lat - a.lat) * toR, dLng = (b.lng - a.lng) * toR;
  const h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(a.lat * toR) * Math.cos(b.lat * toR) * Math.pow(Math.sin(dLng / 2), 2);
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** 주소 → 좌표. 결과는 스크립트 속성에 저장해 다음부터는 다시 조회하지 않음 */
function addCoords_(items, maxLookups) {
  const props = PropertiesService.getScriptProperties();
  const saved = props.getProperties();
  const fresh = {};
  const geocoder = Maps.newGeocoder().setRegion('kr').setLanguage('ko');
  let lookups = 0;
  items.forEach(function (k) {
    const addr = String(k.addr || '').trim();
    if (!addr) return;
    const key = 'geo:' + addr.slice(0, 200);
    let v = saved[key] || fresh[key];
    if (!v && lookups < maxLookups) {
      lookups++;
      try {
        const res = geocoder.geocode(addr);
        const loc = res.status === 'OK' && res.results.length ? res.results[0].geometry.location : null;
        v = loc ? loc.lat + ',' + loc.lng : 'none';
      } catch (e) { v = null; }
      if (v) fresh[key] = v;
    }
    if (v && v !== 'none') {
      const p = v.split(',');
      k._lat = +p[0];
      k._lng = +p[1];
      k._dist = Math.round(km_(HOME, { lat: k._lat, lng: k._lng }) * 100) / 100;
    }
  });
  if (Object.keys(fresh).length) props.setProperties(fresh);
  return lookups;
}

function allItems_(maxLookups) {
  const items = [];
  REGIONS.forEach(function (r) {
    const base = fetchRegion_(r);
    EXTRAS.forEach(function (x) {
      let rows = [];
      try { rows = fetchRegion_(r, x.endpoint); } catch (e) { /* 부가 정보는 실패해도 계속 */ }
      const byCode = {};
      rows.forEach(function (row) { if (row.kindercode) byCode[row.kindercode] = row; });
      base.forEach(function (k) { if (byCode[k.kindercode]) k[x.field] = byCode[k.kindercode]; });
    });
    base.forEach(function (k) { k.region = r.name; items.push(k); });
  });
  addCoords_(items, maxLookups);
  return items;
}

/** 웹 화면(index.html)에서 호출. 좌표 변환은 시트 갱신 때 미리 해 두고, 여기선 조금씩만 */
function getData() {
  const items = allItems_(30);
  const byRegion = {};
  items.forEach(function (k) { (byRegion[k.region] = byRegion[k.region] || []).push(k); });
  return JSON.stringify({
    updated: Utilities.formatDate(new Date(), 'Asia/Seoul', 'yyyy-MM-dd HH:mm'),
    home: HOME,
    regions: REGIONS.map(function (r) { return { name: r.name, items: byRegion[r.name] || [] }; })
  });
}

/** 시트에 동천동 반경 5km 유치원을 가까운 순으로 정리 (전체 목록은 '유치원_전체' 시트) */
function updateSheet() {
  const items = allItems_(1000);
  items.forEach(function (k) {
    const cap4 = parseInt(k.ag4fpcnt, 10), cur3 = parseInt(k.ppcnt3, 10);
    k._vac = isNaN(cap4) || isNaN(cur3) ? '' : cap4 - cur3;
    const bus = k._bus || {};
    k._busText = bus.vhcl_oprn_yn ? (bus.vhcl_oprn_yn === 'Y' ? '운영' + (bus.opra_vhcnt ? ' ' + bus.opra_vhcnt + '대' : '') : '미운영') : '';
  });
  items.sort(function (a, b) { return (a._dist == null ? 999 : a._dist) - (b._dist == null ? 999 : b._dist); });
  const near = items.filter(function (k) { return k._dist != null && k._dist <= RADIUS_KM; });
  writeSheet_('유치원_' + RADIUS_KM + 'km', near);
  writeSheet_('유치원_전체', items);
}

function writeSheet_(name, items) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  const rows = items.map(function (k) {
    return COLUMNS.map(function (c) { return k[c[1]] == null ? '' : k[c[1]]; });
  });
  sheet.clearContents();
  sheet.getRange(1, 1, 1, COLUMNS.length).setValues([COLUMNS.map(function (c) { return c[0]; })]).setFontWeight('bold');
  if (rows.length) sheet.getRange(2, 1, rows.length, COLUMNS.length).setValues(rows);
  sheet.setFrozenRows(1);
}

function doGet() {
  return HtmlService.createHtmlOutputFromFile('index')
    .setTitle('동천동 유치원 찾기')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}
