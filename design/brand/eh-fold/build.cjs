// Editable geometry is the source for every SVG and PNG in this directory.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const OUT = __dirname;
const P = { light: '#B4D1EB', mid: '#82AFD6', dark: '#567FA9', ink: '#263F56', paper: '#FFFFFF', night: '#172531' };
const n = x => +x.toFixed(3);
const poly = points => 'M' + points.map(([x,y]) => `${n(x)},${n(y)}`).join('L') + 'Z';
const left = ([u,v]) => [104 + 152*u, 168 + 88*u + 176*v];
const right = ([u,v]) => [256 + 152*u, 256 - 88*u + 176*v];
const E = [[0,0],[1,0],[1,.24],[.26,.24],[.26,.38],[.84,.38],[.84,.62],[.26,.62],[.26,.76],[1,.76],[1,1],[0,1]];
const H = [[0,0],[.26,0],[.26,.38],[.74,.38],[.74,0],[1,0],[1,1],[.74,1],[.74,.62],[.26,.62],[.26,1],[0,1]];
const crown = poly([[256,80],[408,168],[256,256],[104,168]]);
const aperture = poly([[256,120.48],[338.08,168],[256,215.52],[173.92,168]]);
const geometry = [
  { id: 'crown', d: crown + aperture, fill: P.light },
  { id: 'letter-e', d: poly(E.map(left)), fill: P.mid },
  { id: 'letter-h', d: poly(H.map(right)), fill: P.dark },
];
const mark = (mono, transform = '', solid = false) => {
  // One compound path avoids antialiasing seams between touching monochrome faces.
  const body = mono
    ? `<path fill="${mono}" fill-rule="evenodd" d="${geometry.map(p=>solid && p.id === 'crown' ? crown : p.d).join('')}"/>`
    : geometry.map(p=>`<path fill="${p.fill}" fill-rule="evenodd" d="${solid && p.id === 'crown' ? crown : p.d}"/>`).join('');
  return `<g${transform ? ` transform="${transform}"` : ''}>${body}</g>`;
};
const svg = (body, title = 'EH / Fold', size = 512) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><title>${title}</title>${body}</svg>\n`;
const write = (name, body) => fs.writeFileSync(path.join(OUT,name),body);
async function render(name,size) { await sharp(Buffer.from(fs.readFileSync(path.join(OUT,name+'.svg')))).resize(size,size).png().toFile(path.join(OUT,name+'.png')); }
const text = (x,y,label,size=20,color=P.ink,weight=400) => `<text x="${x}" y="${y}" font-family="Microsoft YaHei, Segoe UI, sans-serif" font-size="${size}" fill="${color}" font-weight="${weight}">${label}</text>`;
const rect = (x,y,w,h,fill,rx=28) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"/>`;
const placed = (x,y,size,mono,solid=false) => mark(mono,`translate(${x} ${y}) scale(${size/512})`,solid);
function android(mono) {
  const paths = mono ? [{fill:mono,d:geometry.map(p=>p.d).join('')}] : geometry;
  return `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n  <group android:scaleX="0.18" android:scaleY="0.18" android:translateX="7.92" android:translateY="7.92">\n${paths.map(p=>`    <path android:fillColor="${p.fill}" android:fillType="evenOdd" android:pathData="${p.d}"/>`).join('\n')}\n  </group>\n</vector>\n`;
}
async function main() {
  write('eh-mark.svg', svg(mark()));
  write('eh-mono.svg', svg(mark(P.ink)));
  write('eh-reversed.svg', svg(mark('#FFFFFF')));
  write('eh-icon.svg', svg(`<rect width="512" height="512" rx="112" fill="#FFFFFF"/>${mark()}`));
  write('eh-icon-dark.svg', svg(`<rect width="512" height="512" rx="112" fill="${P.night}"/>${mark()}`));
  write('eh-solid-study.svg', svg(`<rect width="512" height="512" rx="112" fill="#FFFFFF"/>${mark(null,'',true)}`));
  fs.mkdirSync(path.join(OUT,'android'),{recursive:true});
  write('android/eh_launcher_foreground.xml',android());
  write('android/eh_launcher_monochrome.xml',android('#000000'));
  let board = rect(0,0,1440,1080,'#F1F4F6',0);
  board += text(64,83,'EH / FOLD',48,P.ink,600);
  board += text(66,124,'折面 · 六边形 EH 字母标志',20,'#6D8193');
  board += text(1376,83,'01',20,'#6D8193',500).replace('x="1376"','text-anchor="end" x="1376"');
  board += rect(64,168,760,784,'#FFFFFF',32);
  board += text(102,222,'主标志',18,'#6D8193');
  board += placed(115,250,658);
  board += text(102,896,'E × H',26,P.ink,600);
  board += text(102,928,'共用中轴 / 等距折面 / 菱形留白',17,'#6D8193');
  board += rect(856,168,520,360,P.night,32);
  board += placed(966,177,300);
  board += text(890,497,'深色',17,'#B4C5D3');
  board += rect(856,552,248,400,'#FFFFFF',28);
  board += placed(862,602,236,P.ink);
  board += text(888,896,'单色',19,P.ink,500);
  board += text(888,928,'保留字母与负空间',15,'#6D8193');
  board += rect(1128,552,248,400,'#FFFFFF',28);
  board += placed(1134,602,236,null,true);
  board += text(1160,896,'实心备选',19,P.ink,500);
  board += text(1160,928,'更饱满的立方体',15,'#6D8193');
  board += text(64,1011,'MATTER BLUE',14,'#6D8193',600);
  for (const [i,c] of [P.light,P.mid,P.dark].entries()) {
    board += rect(256+i*196,987,28,28,c,7) + text(296+i*196,1008,c,16,'#6D8193');
  }
  board += text(1376,1011,'EDITABLE SVG · FLAT VECTOR',13,'#6D8193',500).replace('x="1376"','text-anchor="end" x="1376"');
  write('eh-design-board.svg',`<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="1080" viewBox="0 0 1440 1080"><title>EH Fold / Design board</title>${board}</svg>\n`);
  await sharp(Buffer.from(fs.readFileSync(path.join(OUT,'eh-design-board.svg')))).png().toFile(path.join(OUT,'eh-design-board.png'));
  let sizes = rect(0,0,960,360,'#F1F4F6',0) + text(40,53,'EH / 小尺寸与桌面形状',24,P.ink,600);
  for (const [i,size] of [24,32,48,64].entries()) {
    const x = 50+i*120;
    sizes += `<g transform="translate(${x} ${145-size/2}) scale(${size/512})">${rect(0,0,512,512,'#FFFFFF',112)}${mark()}</g>`;
    sizes += text(x,220,`${size} px`,15,'#6D8193');
  }
  sizes += `<defs><clipPath id="round"><circle cx="612" cy="147" r="57"/></clipPath><clipPath id="squircle"><rect x="760" y="90" width="114" height="114" rx="26"/></clipPath></defs>`;
  sizes += `<g clip-path="url(#round)">${rect(555,90,114,114,'#FFFFFF',0)}${placed(555,90,114)}</g>`;
  sizes += `<g clip-path="url(#squircle)">${rect(760,90,114,114,'#FFFFFF',0)}${placed(760,90,114)}</g>`;
  sizes += text(578,249,'圆形预览',15,'#6D8193') + text(780,249,'圆角预览',15,'#6D8193');
  sizes += text(40,315,'纯色路径构成；小尺寸优先使用完整图标，避免再增加描边。',17,'#6D8193');
  write('eh-size-check.svg',`<svg xmlns="http://www.w3.org/2000/svg" width="960" height="360" viewBox="0 0 960 360"><title>EH Fold / Size check</title>${sizes}</svg>\n`);
  await sharp(Buffer.from(fs.readFileSync(path.join(OUT,'eh-size-check.svg')))).png().toFile(path.join(OUT,'eh-size-check.png'));
  await Promise.all(['eh-mark','eh-mono','eh-reversed','eh-icon','eh-icon-dark','eh-solid-study'].map(f=>render(f,1024)));
  for (const size of [48,96,192,512]) {
    await sharp(Buffer.from(fs.readFileSync(path.join(OUT,'eh-icon.svg')))).resize(size,size).png().toFile(path.join(OUT,`eh-icon-${size}.png`));
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
