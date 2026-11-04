const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const OUT = __dirname;
const poly = pts => 'M' + pts.map(p => p.map(n=>+n.toFixed(3)).join(',')).join('L') + 'Z';
const rotate = (pts, angle) => pts.map(([x,y]) => {const a = angle*Math.PI/180;return [256+(x-256)*Math.cos(a)-(y-256)*Math.sin(a),256+(x-256)*Math.sin(a)+(y-256)*Math.cos(a)];});
const q = Math.sqrt(3)*30;
const A = [[256,76],[256+3*q,166],[256+3*q,286],[256+2*q,256],[256+2*q,196],[256,136],[256,232],[256-q,202],[256-q,106]];
const B = [[100,166],[256,76],[412,166],[412,226],[256,316],[204,286],[360,196],[256,136],[152,196],[152,256],[100,286]];
const variants = [
  {id:'01-knot',name:'旋扣',subtitle:'三向回折，开放的中心留白',colors:['#FF6659','#FFB747','#8055D9'],alt:['#B4DF47','#2BBAA2','#6560DF'],paths:[0,120,240].map(a=>poly(rotate(A,a)))},
  {id:'02-link',name:'交叠',subtitle:'两条折带，斜向穿插的空间感',colors:['#F15F78','#7858E8'],alt:['#FF9D3F','#EF5B88'],paths:[poly(B),poly(rotate(B,180))]},
];
const mark = (v,mono) => mono ? `<path d="${v.paths.join('')}" fill="${mono}"/>` : v.paths.map((d,i)=>`<path d="${d}" fill="${v.colors[i]}" stroke="${v.colors[i]}" stroke-width="0.35" stroke-linejoin="round"/>`).join('');
const svg = (body,w=512,h=512) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`;
const rect=(x,y,w,h,fill,rx=0)=>`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${fill}" rx="${rx}"/>`;
const text=(x,y,s,size=20,fill='#343348',weight=400)=>`<text x="${x}" y="${y}" font-family="Microsoft YaHei, sans-serif" font-size="${size}" fill="${fill}" font-weight="${weight}">${s}</text>`;
const at=(v,x,y,s,mono)=>`<g transform="translate(${x} ${y}) scale(${s/512})">${mark(v,mono)}</g>`;
async function main(){
  let body=rect(0,0,1440,1024,'#F5F3F0')+text(56,76,'EH / ABSTRACT',42,'#343348',600)+text(56,119,'以折线与穿插关系抽象字母，让符号先于字母被感知。',20,'#85808E');
  for(let i=0;i<variants.length;i++){
    const v=variants[i],x=48+i*688,alt={...v,colors:v.alt};
    const assets={
      'mark':mark(v),
      'mono':mark(v,'#343348'),
      'icon':rect(0,0,512,512,'#FFFFFF',112)+mark(v),
      'dark':rect(0,0,512,512,'#242133',112)+mark(v),
      'alternate':rect(0,0,512,512,'#FFFFFF',112)+mark(alt),
    };
    for(const [suffix,content] of Object.entries(assets)){
      const filename=v.id+'-'+suffix;
      const contentSvg=svg(`<title>EH / ${v.name} / ${suffix}</title>${content}`);
      fs.writeFileSync(path.join(OUT,filename+'.svg'),contentSvg);
      await sharp(Buffer.from(contentSvg)).resize(1024,1024).png().toFile(path.join(OUT,filename+'.png'));
    }
    body+=rect(x,164,656,800,'#FFFFFF',28)+text(x+36,216,'0'+(i+1)+' / '+v.name,26,'#343348',600)+text(x+36,251,v.subtitle,17,'#85808E')+at(v,x+68,268,520);
    body+=`<path d="M${x+36},744H${x+620}" stroke="#EEEAF0"/>`;
    body+=at(v,x+60,764,144,'#343348');
    body+=rect(x+256,764,144,144,'#242133',30)+at(v,x+256,764,144);
    body+=at(alt,x+452,764,144);
    body+=text(x+96,938,'单色轮廓',15,'#85808E')+text(x+312,938,'深色',15,'#85808E')+text(x+488,938,'另一组配色',15,'#85808E');
  }
  body+=text(56,1000,'VECTOR STUDIES / 02',13,'#85808E',500)+text(1120,1000,'纯矢量 · 透明负空间 · 可换色',14,'#85808E');
  fs.writeFileSync(path.join(OUT,'eh-design-board.svg'),svg(body,1440,1024));
  await sharp(Buffer.from(svg(body,1440,1024))).png().toFile(path.join(OUT,'eh-design-board.png'));
  let checks=rect(0,0,1120,448,'#F5F3F0')+text(40,48,'EH / 小尺寸对照',24,'#343348',600);
  for(let i=0;i<variants.length;i++){
    const v=variants[i],y=90+i*168;
    checks+=text(40,y+56,v.name,18,'#343348',600);
    for(const [j,size] of [24,32,48,64,96].entries()){
      const x=160+j*152;
      checks+=rect(x,y+48-size/2,size,size,'#FFFFFF',size*.22)+at(v,x,y+48-size/2,size)+text(x,y+128,size+' px',14,'#85808E');
    }
    checks+=at(v,930,y,96,'#343348')+text(944,y+128,'单色',14,'#85808E');
  }
  fs.writeFileSync(path.join(OUT,'eh-size-check.svg'),svg(checks,1120,448));
  await sharp(Buffer.from(svg(checks,1120,448))).png().toFile(path.join(OUT,'eh-size-check.png'));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
