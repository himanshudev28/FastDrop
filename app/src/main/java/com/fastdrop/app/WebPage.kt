package com.fastdrop.app

/**
 * The page browsers see at http://<ip>:53532/<token>/ . One self-contained document:
 * no external URLs, no dollar signs (this is a Kotlin raw string), relative API paths only.
 */
object WebPage {
    fun html(): String = HTML

    private const val HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark">
<meta name="theme-color" content="#07080D">
<title>FastDrop</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%237C6CFF'/%3E%3Cpath d='M18 4L8 18h7l-1 10 10-14h-7z' fill='white'/%3E%3C/svg%3E">
<style>
:root{--bg:#07080D;--s1:#11131C;--s2:#181B27;--s3:#222636;--line:rgba(255,255,255,.12);--text:#F3F5FA;--muted:#8E95AB;--faint:#5A6075;--a:#7C6CFF;--b:#4FA3FF;--ok:#34D399;--bad:#FF5C7A;--g:linear-gradient(135deg,#7C6CFF,#4FA3FF)}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html{background:var(--bg);-webkit-text-size-adjust:100%}
body{margin:0;min-height:100vh;background:var(--bg);color:var(--text);font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;-webkit-font-smoothing:antialiased;overflow-x:hidden}
.glow{position:fixed;inset:0;pointer-events:none;z-index:0;overflow:hidden}
.glow i{position:absolute;border-radius:50%;filter:blur(70px);opacity:.35;will-change:transform}
.glow i:nth-child(1){width:340px;height:340px;background:#7C6CFF;top:-120px;left:-80px;animation:drift1 18s ease-in-out infinite alternate}
.glow i:nth-child(2){width:300px;height:300px;background:#4FA3FF;top:30%;right:-120px;opacity:.22;animation:drift2 22s ease-in-out infinite alternate}
@keyframes drift1{to{transform:translate(60px,80px)}}
@keyframes drift2{to{transform:translate(-70px,-60px)}}
.wrap{position:relative;z-index:1;max-width:720px;margin:0 auto;padding:calc(env(safe-area-inset-top) + 20px) calc(env(safe-area-inset-right) + 16px) calc(env(safe-area-inset-bottom) + 40px) calc(env(safe-area-inset-left) + 16px)}
header{display:flex;align-items:center;margin:4px 0 22px}
.logo{width:44px;height:44px;border-radius:14px;background:var(--g);display:flex;align-items:center;justify-content:center;box-shadow:0 8px 24px rgba(124,108,255,.45);flex:none}
.logo svg{width:24px;height:24px}
.brand{margin-left:12px;min-width:0}
.brand b{display:block;font-size:22px;font-weight:900;letter-spacing:-.02em}
.brand span{display:block;color:var(--muted);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
h2{margin:26px 4px 10px;font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
.card{background:rgba(17,19,28,.86);border:1px solid var(--line);border-radius:22px;-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px)}
.rise{opacity:0;transform:translateY(12px);animation:rise .5s cubic-bezier(.2,0,0,1) forwards;animation-delay:calc(var(--i,0) * 45ms)}
@keyframes rise{to{opacity:1;transform:none}}
.list{display:flex;flex-direction:column;gap:10px}
.file{display:flex;align-items:center;padding:12px;gap:12px}
.thumb{width:52px;height:52px;border-radius:14px;flex:none;background:var(--s2);display:flex;align-items:center;justify-content:center;overflow:hidden;position:relative}
.thumb svg{width:26px;height:26px}
.thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:0;transition:opacity .3s}
.thumb img.on{opacity:1}
.meta{flex:1;min-width:0}
.meta b{display:block;font-weight:600;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta span{color:var(--muted);font-size:13px}
.btn{appearance:none;-webkit-appearance:none;border:0;cursor:pointer;color:#fff;font-weight:600;font-size:14px;line-height:1;font-family:inherit;padding:12px 16px;border-radius:14px;background:var(--g);text-decoration:none;display:inline-flex;align-items:center;gap:6px;flex:none;box-shadow:0 6px 18px rgba(79,163,255,.28);transition:transform .15s,box-shadow .15s}
.btn svg{width:16px;height:16px}
.btn:active{transform:scale(.96)}
.btn.ghost{background:var(--s3);box-shadow:none}
.btn:focus-visible,.drop:focus-visible{outline:2px solid #fff;outline-offset:2px}
.app{display:flex;align-items:center;gap:14px;padding:16px;background:linear-gradient(135deg,rgba(124,108,255,.22),rgba(79,163,255,.12)),rgba(17,19,28,.86)}
.app .logo{width:52px;height:52px}
.empty{padding:26px 18px;text-align:center;color:var(--muted)}
.drop{display:block;padding:28px 18px;text-align:center;border:1.5px dashed rgba(124,108,255,.55);border-radius:22px;background:rgba(124,108,255,.07);cursor:pointer;transition:background .2s,border-color .2s,transform .2s}
.drop.over{background:rgba(124,108,255,.2);border-color:var(--b);transform:scale(1.01)}
.drop .ic{width:52px;height:52px;margin:0 auto 10px;border-radius:16px;background:var(--g);display:flex;align-items:center;justify-content:center;animation:bob 3s ease-in-out infinite}
.drop .ic svg{width:26px;height:26px}
@keyframes bob{50%{transform:translateY(-4px)}}
.drop b{display:block;font-size:16px}
.drop span{color:var(--muted);font-size:13px}
.drop input{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none}
.up{padding:12px 14px}
.up .row{display:flex;align-items:center;gap:10px}
.up .nm{flex:1;min-width:0;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.up .st{font-size:13px;color:var(--muted);flex:none}
.bar{height:6px;border-radius:3px;background:var(--s3);margin-top:10px;overflow:hidden}
.bar i{display:block;height:100%;width:0;border-radius:3px;background:var(--g);transition:width .15s linear;position:relative;overflow:hidden}
.bar i::after{content:"";position:absolute;inset:0;background:linear-gradient(90deg,transparent,rgba(255,255,255,.45),transparent);transform:translateX(-100%);animation:shim 1.2s linear infinite}
@keyframes shim{to{transform:translateX(100%)}}
.up.done .st{color:var(--ok)}.up.done .bar i{background:var(--ok)}.up.done .bar i::after,.up.fail .bar i::after{display:none}
.up.fail .st{color:var(--bad)}.up.fail .bar i{background:var(--bad)}
.retry{background:none;border:0;color:var(--b);font-weight:600;font-size:13px;font-family:inherit;cursor:pointer;padding:4px}
.note{color:var(--muted);font-size:12.5px;text-align:center;margin:26px 8px 0}
.off{display:none;margin:16px 0;padding:14px;color:var(--bad);text-align:center}
@media (prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}.rise{opacity:1;transform:none}}
</style>
</head>
<body>
<div class="glow"><i></i><i></i></div>
<div class="wrap">
  <header class="rise">
    <div class="logo"><svg viewBox="0 0 24 24"><path fill="#fff" d="M13.5 2 5 13.5h5.5L9.5 22 19 9.5h-5.8z"/></svg></div>
    <div class="brand"><b>FastDrop</b><span id="dev">Connecting...</span></div>
  </header>
  <div class="card off" id="off">Can't reach the phone. Check you are still on the same Wi-Fi and the share is still open.</div>
  <div id="appbox"></div>
  <h2 id="fh" style="display:none">Shared with you</h2>
  <div class="list" id="files"></div>
  <h2 id="uh">Send to this phone</h2>
  <label class="drop rise" id="drop" style="--i:3" tabindex="0">
    <div class="ic"><svg viewBox="0 0 24 24"><path fill="#fff" d="M12 3 6.5 8.5l1.4 1.4L11 6.8V16h2V6.8l3.1 3.1 1.4-1.4zM5 18v3h14v-3h-2v1H7v-1z"/></svg></div>
    <b>Choose files</b>
    <span id="dz">or drop them here</span>
    <input type="file" id="pick" multiple>
  </label>
  <div class="list" id="ups" style="margin-top:12px"></div>
  <p class="note">Files go straight between devices on your Wi-Fi. Nothing is sent over the internet. Keep this page open while uploading.</p>
</div>
<script>
(function(){
  var ICONS={
    image:'<svg viewBox="0 0 24 24"><path fill="#F472B6" d="M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3 3.5-4.5 4.5 6H5z"/></svg>',
    video:'<svg viewBox="0 0 24 24"><path fill="#FB923C" d="M4 5h11a2 2 0 0 1 2 2v3l5-3v10l-5-3v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"/></svg>',
    audio:'<svg viewBox="0 0 24 24"><path fill="#A78BFA" d="M12 3v10.6A4 4 0 1 0 14 17V7h4V3z"/></svg>',
    apk:'<svg viewBox="0 0 24 24"><path fill="#22C55E" d="M6 18c0 .6.4 1 1 1h1v3.5a1.5 1.5 0 0 0 3 0V19h2v3.5a1.5 1.5 0 0 0 3 0V19h1c.6 0 1-.4 1-1V8H6zM3.5 8A1.5 1.5 0 0 0 2 9.500v7a1.5 1.5 0 0 0 3 0v-7A1.5 1.5 0 0 0 3.500 8zm17 0A1.5 1.5 0 0 0 19 9.500v7a1.5 1.5 0 0 0 3 0v-7A1.5 1.5 0 0 0 20.500 8zM15.500 2.200l1.300-1.300-.7-.7-1.500 1.500A5.600 5.600 0 0 0 12 1.200c-.8 0-1.600.2-2.200.5L8.300.2l-.7.700 1.300 1.300A5.500 5.500 0 0 0 6.100 7h11.800a5.500 5.500 0 0 0-2.400-4.800z"/></svg>',
    other:'<svg viewBox="0 0 24 24"><path fill="#38BDF8" d="M6 2a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm7 1.500L18.500 9H13z"/></svg>'
  };
  var DL='<svg viewBox="0 0 24 24"><path fill="#fff" d="M11 3h2v9.200l3.100-3.100 1.400 1.400L12 16 6.500 10.500l1.400-1.400L11 12.200zM5 18h14v3H5z"/></svg>';
  function $(id){return document.getElementById(id);}
  function esc(s){return String(s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function fmt(n){
    if(n<1024)return n+' B';
    var u=['KB','MB','GB','TB'],i=-1;
    do{n/=1024;i++;}while(n>=1024&&i<3);
    return (n>=100?n.toFixed(0):n.toFixed(1))+' '+u[i];
  }
  function shortName(n){var i=n.lastIndexOf('/');return i<0?n:n.substring(i+1);}

  function req(method,url,done){
    var x=new XMLHttpRequest();
    x.open(method,url,true);
    x.onload=function(){done(x.status,x.responseText);};
    x.onerror=function(){done(0,'');};
    x.timeout=15000;x.ontimeout=x.onerror;
    x.send();
  }

  var loaded=false,sig='';
  function load(){
    req('GET','api/list',function(st,txt){
      if(st!==200){$('off').style.display=loaded?'block':'block';if(!loaded)$('dev').textContent='Not connected';return;}
      $('off').style.display='none';
      var d;try{d=JSON.parse(txt);}catch(e){return;}
      var sg=JSON.stringify(d.files||[])+'|'+(d.app?1:0);
      if(!loaded||sg!==sig){loaded=true;sig=sg;$('files').innerHTML='';$('appbox').innerHTML='';render(d);}
      $('dev').textContent=d.name||'Phone';
    });
  }

  function render(d){
    $('dev').textContent=d.name||'Phone';
    if(d.app){
      var a=document.createElement('div');
      a.className='card app rise';a.style.setProperty('--i',1);
      a.innerHTML='<div class="logo"><svg viewBox="0 0 24 24"><path fill="#fff" d="M13.5 2 5 13.5h5.5L9.5 22 19 9.5h-5.8z"/></svg></div>'+
        '<div class="meta"><b>Get the FastDrop app</b><span>Android &middot; '+fmt(d.app.s)+'</span></div>'+
        '<a class="btn" href="app.apk">'+DL+'Install</a>';
      $('appbox').appendChild(a);
    }
    var files=d.files||[];
    var box=$('files');
    if(files.length){$('fh').style.display='block';}
    for(var i=0;i<files.length;i++){box.appendChild(fileRow(files[i],i));}
    if(!files.length&&!d.app){$('fh').style.display='none';}
  }

  function fileRow(f,i){
    var r=document.createElement('div');
    r.className='card file rise';r.style.setProperty('--i',Math.min(i+2,12));
    var th=document.createElement('div');
    th.className='thumb';th.innerHTML=ICONS[f.k]||ICONS.other;
    if(f.th){
      var im=new Image();
      im.alt='';im.decoding='async';
      im.onload=function(){im.className='on';};
      im.onerror=function(){if(im.parentNode)im.parentNode.removeChild(im);};
      im.src=f.u+'?inline=1';
      th.appendChild(im);
    }
    var m=document.createElement('div');
    m.className='meta';
    m.innerHTML='<b>'+esc(shortName(f.n))+'</b><span>'+fmt(f.s)+(f.n.indexOf('/')>=0?' &middot; '+esc(f.n.substring(0,f.n.lastIndexOf('/'))):'')+'</span>';
    var a=document.createElement('a');
    a.className='btn';a.href=f.u;a.setAttribute('download',shortName(f.n));
    a.innerHTML=DL+'Save';
    r.appendChild(th);r.appendChild(m);r.appendChild(a);
    return r;
  }

  // ---------- uploads ----------
  var queue=[],active=0,MAX=3;
  function addFiles(list){
    for(var i=0;i<list.length;i++){queue.push(makeUp(list[i]));}
    pump();
  }
  function makeUp(file){
    var el=document.createElement('div');
    el.className='card up rise';
    el.innerHTML='<div class="row"><div class="nm">'+esc(file.name)+'</div><div class="st">Waiting</div></div><div class="bar"><i></i></div>';
    $('ups').insertBefore(el,$('ups').firstChild);
    return {file:file,el:el,st:el.querySelector('.st'),bar:el.querySelector('.bar i')};
  }
  function pump(){
    while(active<MAX&&queue.length){send(queue.shift());}
  }
  function send(u){
    active++;
    u.el.className='card up';
    u.st.textContent='0%';u.bar.style.width='0%';
    var x=new XMLHttpRequest();
    x.open('PUT','up?name='+encodeURIComponent(u.file.name),true);
    x.setRequestHeader('Content-Type','application/octet-stream');
    x.upload.onprogress=function(e){
      if(e.lengthComputable&&e.total>0){
        var p=Math.round(e.loaded*100/e.total);
        u.bar.style.width=p+'%';u.st.textContent=p+'% · '+fmt(e.loaded);
      }
    };
    function finish(ok,msg){
      if(u.fin)return;u.fin=true;active--;
      if(ok){
        u.el.className='card up done';u.bar.style.width='100%';u.st.textContent='Done ✓';
      }else{
        u.el.className='card up fail';u.st.innerHTML=esc(msg||'Failed')+' <button class="retry">Retry</button>';
        u.st.querySelector('.retry').onclick=function(){u.fin=false;queue.push(u);pump();};
      }
      pump();
    }
    x.onload=function(){
      if(x.status>=200&&x.status<300)finish(true);
      else finish(false,x.status===507?'No space left':'Failed ('+x.status+')');
    };
    x.onerror=function(){finish(false,'Connection lost');};
    x.onabort=x.onerror;
    try{x.send(u.file);}catch(e){finish(false,'Could not read file');}
  }

  var pick=$('pick'),drop=$('drop');
  pick.onchange=function(){if(pick.files&&pick.files.length)addFiles(pick.files);pick.value='';};
  drop.onkeydown=function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();pick.click();}};
  ['dragenter','dragover'].forEach(function(n){drop.addEventListener(n,function(e){e.preventDefault();drop.className='drop over';});});
  ['dragleave','drop'].forEach(function(n){drop.addEventListener(n,function(e){e.preventDefault();drop.className='drop';});});
  drop.addEventListener('drop',function(e){if(e.dataTransfer&&e.dataTransfer.files)addFiles(e.dataTransfer.files);});
  if(!('ondrop' in window)||/iPhone|iPad|Android/i.test(navigator.userAgent)){$('dz').textContent='photos, videos or any file';}

  load();
  setInterval(load,8000);
})();
</script>
</body>
</html>
"""
}
