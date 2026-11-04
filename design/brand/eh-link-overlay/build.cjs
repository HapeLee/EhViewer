// Pink upper plate over a purple foundation, with one small lower-right window.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const OUT = __dirname;
const n = v => +v.toFixed(3);
function roundedPolygon(pts, cut=11) {
  const corners = pts.map((p,i)=>{
    const a=pts[(i+pts.length-1)%pts.length], b=pts[(i+1)%pts.length];
    const la=Math.hypot(a[0]-p[0],a[1]-p[1]),lb=Math.hypot(b[0]-p[0],b[1]-p[1]);
    const d=Math.min(cut,la*.24,lb*.24);
    return {p, before:[p[0]+(a[0]-p[0])*d/la,p[1]+(a[1]-p[1])*d/la],after:[p[0]+(b[0]-p[0])*d/lb,p[1]+(b[1]-p[1])*d/lb]};
  });
  const xy=p=>p.map(n).join(',');
  return `M${xy(corners[0].before)}`+corners.map(c=>`L${xy(c.before)}Q${xy(c.p)} ${xy(c.after)}`).join('')+'Z';
}
const backing = roundedPolygon([[100,166],[256,76],[412,166],[412,346],[256,436],[100,346]]);
const windowPath = roundedPolygon([[302,290],[360,256.5],[360,298.5],[302,332]],5);
const pinkPlate = roundedPolygon([[100,166],[256,76],[412,166],[412,292],[256,382],[100,292]]);
const pink = pinkPlate + windowPath;
const purple = backing;
function defs(id,dark=false) {
  return `<defs>
    <linearGradient id="${id}-tile" x1=".15" y1="0" x2=".8" y2="1"><stop stop-color="${dark?'#393044':'#FFFDFF'}"/><stop offset=".52" stop-color="${dark?'#272231':'#F5F1FA'}"/><stop offset="1" stop-color="${dark?'#17151F':'#E9E4F1'}"/></linearGradient>
    <linearGradient id="${id}-pink" gradientUnits="userSpaceOnUse" x1="160" y1="92" x2="350" y2="382"><stop stop-color="#FF9BAC"/><stop offset=".44" stop-color="#F86F90"/><stop offset="1" stop-color="#DD4979"/></linearGradient>
    <linearGradient id="${id}-purple" gradientUnits="userSpaceOnUse" x1="158" y1="220" x2="326" y2="439"><stop stop-color="#AC94FF"/><stop offset=".48" stop-color="#8664F0"/><stop offset="1" stop-color="#6041CC"/></linearGradient>
    <linearGradient id="${id}-rim" x1="0" y1="0" x2=".55" y2="1"><stop stop-color="#FFFFFF" stop-opacity=".65"/><stop offset=".46" stop-color="#FFFFFF" stop-opacity=".14"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
    <linearGradient id="${id}-edge" x1="0" y1="0" x2=".2" y2="1"><stop stop-color="#FFFFFF" stop-opacity="${dark?'.13':'.94'}"/><stop offset=".6" stop-color="#FFFFFF" stop-opacity=".12"/><stop offset="1" stop-color="${dark?'#000000':'#C8BFD7'}" stop-opacity=".36"/></linearGradient>
    <filter id="${id}-ambient" x="-30%" y="-25%" width="160%" height="165%" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="8"/><feOffset dy="7"/></filter>
    <filter id="${id}-ground" x="-25%" y="-20%" width="150%" height="150%" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="2.2"/><feOffset dy="3"/></filter>
    <filter id="${id}-contact" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="1.6"/><feOffset dx="0" dy="2.6"/></filter>
    <clipPath id="${id}-purple-clip"><path d="${purple}"/></clipPath>
  </defs>`;
}
function mark(id,shadows=true) {
  return `${shadows?`<path d="${backing}" fill="#5A337C" opacity=".10" filter="url(#${id}-ambient)"/><path d="${backing}" fill="#342147" opacity=".11" filter="url(#${id}-ground)"/>`:''}
    <path d="${purple}" transform="translate(0 2.5)" fill="#5536B6"/>
    <path d="${purple}" fill="url(#${id}-purple)"/>
    <g clip-path="url(#${id}-purple-clip)"><path d="${pink}" fill-rule="evenodd" fill="#48204C" opacity=".23" filter="url(#${id}-contact)"/></g>
    <path d="${pink}" transform="translate(0 2.5)" fill-rule="evenodd" fill="#C34570"/>
    <path d="${pink}" fill-rule="evenodd" fill="url(#${id}-pink)"/>
    <path d="${purple}" fill="none" stroke="url(#${id}-rim)" stroke-width="1.25"/>
    <path d="${pink}" fill="none" stroke="url(#${id}-rim)" stroke-width=".85"/>`;
}
function icon(id,dark=false,square=false) {
  return `${defs(id,dark)}<rect x="0" y="0" width="512" height="512" rx="${square?0:112}" fill="url(#${id}-tile)"/>${!square?`<rect x=".8" y=".8" width="510.4" height="510.4" rx="111.2" fill="none" stroke="url(#${id}-edge)" stroke-width="1.6"/>`:''}${mark(id)}`;
}
const svg=(body,w=512,h=512,title='EH / Overlay')=>`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><title>${title}</title>${body}</svg>\n`;
const text=(x,y,label,size=20,color='#41394E',weight=400)=>`<text x="${x}" y="${y}" font-family="Microsoft YaHei, Segoe UI, sans-serif" font-size="${size}" fill="${color}" font-weight="${weight}">${label}</text>`;
const atIcon=(id,x,y,size,dark=false)=>`<g transform="translate(${x} ${y}) scale(${size/512})">${icon(id,dark)}</g>`;
async function save(name,body,w=512,h=512,size=1024){
  const content=svg(body,w,h);
  fs.writeFileSync(path.join(OUT,name+'.svg'),content);
  await sharp(Buffer.from(content)).resize({width:size}).png().toFile(path.join(OUT,name+'.png'));
}
async function main(){
  await save('eh-link-icon',icon('light'));
  await save('eh-link-dark',icon('dark',true));
  await save('eh-link-app-square',icon('square',false,true));
  await save('eh-link-mark',defs('mark')+mark('mark'));
  // A knockout retains the small window when both layers use a single ink.
  await save('eh-link-mono',`<path d="${backing+windowPath}" fill-rule="evenodd" fill="#383047"/>`);
  for(const size of [48,64,96,192,512]) {
    await sharp(Buffer.from(svg(icon('size'+size)))).resize(size,size).png().toFile(path.join(OUT,`eh-link-icon-${size}.png`));
  }
  let board=`<rect width="1440" height="1024" fill="#ECE9F0"/>`;
  board+=text(64,82,'EH / OVERLAY',44,'#41394E',600)+text(66,125,'粉色上层 · 紫色底层 · 右下角小窗',20,'#8A8096');
  board+=atIcon('board-light',64,186,704)+text(72,939,'统一叠层 / 局部透窗 / 轻薄接触阴影',20,'#746980');
  board+=atIcon('board-dark',896,186,384,true)+text(901,612,'深色版本',19,'#746980');
  board+=text(901,710,'桌面尺寸',19,'#746980');
  for(const [i,size] of [48,64,96].entries()) {
    const x=900+i*128;
    board+=atIcon('preview'+size,x,754+(96-size)/2,size)+text(x,895,size+' px',16,'#8A8096');
  }
  board+=text(64,994,'EDITABLE SVG · 1024 PX PNG',13,'#8A8096',500);
  await save('eh-refinement-board',board,1440,1024,1440);
  let check=`<rect width="1120" height="330" fill="#ECE9F0"/>`+text(36,50,'EH / 叠层 · 尺寸检查',24,'#41394E',600);
  for(const [i,size] of [24,32,48,64,96,128].entries()) {
    const x=48+i*174;
    check+=atIcon('check'+size,x,120+(128-size)/2,size)+text(x,284,size+' px',16,'#8A8096');
  }
  await save('eh-size-check',check,1120,330,1120);
}
main().catch(e=>{console.error(e);process.exitCode=1;});
