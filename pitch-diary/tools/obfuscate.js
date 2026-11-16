// 배포용 빌드: CSS 압축 + JS 압축/난독화 → 한 파일
const fs = require('fs'), path = require('path');
const JO = require('javascript-obfuscator'), { minify } = require('terser'), csso = require('csso');
(async () => {
  const src = fs.readFileSync(process.argv[2], 'utf8');
  let html = src.replace(/<a class="t-diary" href="index.html">\$\{ic\('book-open'\)\} 직관 일기<\/a>/, '');
  html = html.replace(/<style>([\s\S]*?)<\/style>/, (m, css) => `<style>${csso.minify(css).css}</style>`);
  const i = html.lastIndexOf('<script>'), j = html.lastIndexOf('</script>');
  const js = html.slice(i + 8, j);
  const min = await minify(js, { compress: { passes: 2 }, mangle: true, format: { comments: /Lucide|ISC/ } });
  const obf = JO.obfuscate(min.code, {
    compact: true, target: 'browser', stringArray: true, stringArrayEncoding: ['base64'], stringArrayThreshold: 0.75,
    stringArrayRotate: true, stringArrayShuffle: true, splitStrings: false, identifierNamesGenerator: 'hexadecimal',
    controlFlowFlattening: false, deadCodeInjection: false, selfDefending: false, renameGlobals: false, unicodeEscapeSequence: false,
  }).getObfuscatedCode();
  html = html.slice(0, i + 8) + '/*! Pitch Career · Icons: Lucide (ISC) */' + obf + html.slice(j);
  html = html.replace(/<!--[\s\S]*?-->/g, '').replace(/\n\s*\n/g, '\n');
  fs.writeFileSync(process.argv[3], html);
  console.log('in', (src.length / 1024).toFixed(0) + 'KB', '→ out', (html.length / 1024).toFixed(0) + 'KB');
})();
