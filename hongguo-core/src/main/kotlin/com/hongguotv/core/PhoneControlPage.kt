// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** All rendering stays on the phone. Artwork thumbnails arrive through the TV session; no remote image requests or video on the phone. */
internal object PhoneControlPage {
    fun html(nonce: String) = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>hongguoTV · 手机控制台</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#203044;color:#f5f5f7;font:16px system-ui,sans-serif}main{max-width:700px;margin:auto;padding:20px 18px 40px}h1{font-size:23px;margin:6px 0}h2{font-size:21px}h3{font-size:17px}button,input,select,a.action{font:inherit;border-radius:10px;padding:11px 13px;border:1px solid #475063;background:#30435a;color:inherit;min-height:44px}button{cursor:pointer}button:disabled{opacity:.45}button.primary,a.action.primary{background:#e95342;border-color:#e95342}a.action{display:inline-block;text-decoration:none}a{color:#ffad9b}[hidden]{display:none!important}.row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:12px 0}input[type=search],input[type=tel],input.code{flex:1;min-width:0;width:100%}.searchbox{display:flex;gap:8px;margin:18px 0 8px}.card{background:#2c3e54;border:1px solid #465b76;border-radius:14px;padding:16px;margin:12px 0}.muted{color:#acb8ca;font-size:14px;line-height:1.6}#notice{color:#ffd0a0;margin:8px 0;line-height:1.5}#notice:empty{display:none}#rows h3{margin:0 0 8px}label{display:inline-flex;align-items:center;gap:6px}input[type=number]{width:84px}.tabs{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;position:sticky;top:0;background:#203044;padding:14px 0;z-index:2}.tabs button{border-color:transparent;background:none}.tabs button[aria-selected=true],.library-tabs button[aria-selected=true],button[aria-pressed=true]{background:#392822;border-color:#df735b;color:#ffb19d}.subtabs{display:flex;gap:8px;margin:12px 0}.subtabs button{flex:1}.chips{display:flex;flex-wrap:wrap;gap:8px;padding:4px 0 8px}.chips button{white-space:normal;max-width:100%;overflow-wrap:anywhere;font-size:14px;padding:8px 13px;flex-shrink:0}.filter h3{font-size:14px;margin:8px 0 2px;color:#acb8ca}.suggestions{display:grid;gap:4px}.suggestions button{text-align:left;background:#2c3e54;border-color:transparent}summary{cursor:pointer;padding:12px 0;min-height:44px}#qrImage{width:220px;max-width:100%;background:white;padding:8px;border-radius:12px}.library-tabs{display:grid;grid-template-columns:repeat(4,1fr);gap:6px}.library-tabs button{padding:10px 4px}.filter{background:#2c3e54;border:1px solid #465b76;border-radius:12px;padding:6px 12px;margin:10px 0}.filter summary{font-size:14px;color:#acb8ca}.filter-more{margin:4px 0 8px;color:#ffb19d}.series-head{display:flex;gap:14px;align-items:flex-start}.series-info{min-width:0;overflow-wrap:anywhere}.poster{flex:0 0 84px;width:84px;height:112px;border-radius:8px;overflow:hidden;background:#203044;display:flex;align-items:center;justify-content:center;color:#acb8ca;font-size:13px}.poster img{width:100%;height:100%;object-fit:cover}.series-info h3{margin:0 0 8px}.series-info p{margin:0}.playback-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:16px 0}small{display:block}header .muted{margin:4px 0}.empty{padding:20px 0;color:#acb8ca}#loginPanel input{margin:5px 0}#loginNote{min-height:22px;line-height:1.5;color:#ffd0a0}
</style></head>
<body><main><header><!-- brand:start --><h1 style="display:flex;align-items:center;gap:10px"><img width="48" height="40" style="object-fit:contain;image-rendering:pixelated" src="data:image/png;base64,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" alt=""/>hongguoTV</h1><!-- brand:end --><p class="muted" id="connection">正在连接电视…</p></header>
<nav class="tabs" role="tablist" aria-label="主要功能"><button id="tab-browse" role="tab" aria-controls="panel-browse" data-tab="browse" aria-selected="true">找剧</button><button id="tab-player" role="tab" aria-controls="panel-player" data-tab="player" aria-selected="false">播放</button><button id="tab-library" role="tab" aria-controls="panel-library" data-tab="library" aria-selected="false">片单</button><button id="tab-account" role="tab" aria-controls="panel-account" data-tab="account" aria-selected="false">账户</button></nav>
<p id="notice" role="status" aria-live="polite"></p>
<section id="panel-browse" role="tabpanel" aria-labelledby="tab-browse">
<div class="searchbox"><input id="query" type="search" maxlength="80" autocomplete="off" placeholder="搜剧名、演员" aria-label="搜索剧名或演员" aria-controls="suggestions"><button class="primary" id="search">搜索</button></div>
<div id="suggestions" class="suggestions" aria-label="搜索联想" hidden></div>
<div class="subtabs"><button id="searchMode" aria-pressed="true">搜索</button><button id="discoverMode" aria-pressed="false">按标签找剧</button></div>
<div id="filters" hidden></div><div id="browseHost"></div>
</section>
<section id="panel-player" role="tabpanel" aria-labelledby="tab-player" hidden>
<h2 id="playing">电视未在剧集播放页</h2><p id="position" class="muted"></p>
<div class="playback-grid"><button data-cmd="previous">上一集</button><button class="primary" id="togglePlay">暂停</button><button data-cmd="next">下一集</button></div>
<div class="row"><button data-cmd="backward">−15 秒</button><button data-cmd="forward">+15 秒</button></div>
<div class="card"><div class="row"><label>倍速<select id="speed"><option>0.75</option><option selected>1</option><option>1.25</option><option>1.5</option><option>1.75</option><option>2</option><option>3</option></select></label><label>清晰度<select id="quality"><option>480</option><option>720</option><option selected>1080</option></select></label></div><div class="row"><label>第<input id="episode" type="number" min="1" value="1">集</label><button id="jump">播放此集</button></div></div>
<p class="muted">在「找剧」或「片单」选一部剧，即可在电视播放。</p></section>
<section id="panel-library" role="tabpanel" aria-labelledby="tab-library" hidden>
<h2>我的片单</h2><nav class="library-tabs" role="tablist" aria-label="片单分类"><button id="library-favorites" role="tab" aria-controls="libraryHost" data-library="favorites" aria-selected="true">收藏</button><button id="library-history" role="tab" aria-controls="libraryHost" data-library="history" aria-selected="false">历史</button><button id="library-online" role="tab" aria-controls="libraryHost" data-library="online" aria-selected="false">预约</button><button id="library-later" role="tab" aria-controls="libraryHost" data-library="later" aria-selected="false">稍后看</button></nav>
<div id="loginGate" class="card" hidden><p>登录红果后，在这里查看已上线的预约。</p><button id="goLogin" class="primary">在手机登录</button></div><div id="libraryHost" role="tabpanel" aria-labelledby="library-favorites"></div></section>
<section id="panel-account" role="tabpanel" aria-labelledby="tab-account" hidden><h2>红果账户</h2><p id="accountLabel"></p><p id="accountStatus" class="muted"></p>
<div id="loginPanel" class="card"><h3>在手机登录，同步到电视</h3><button id="prepareApp" class="primary">红果 App 授权登录</button><p><a id="openApp" class="action primary" hidden rel="noreferrer">打开红果 App 授权</a></p><p id="appHint" class="muted">手机红果 App 需先登录。授权后返回此页，电视自动登录。</p><details id="smsDetails"><summary>手机号验证码登录</summary><p class="muted">使用红果手机号验证，成功后这台电视自动登录。</p><label for="mobile">手机号（支持 +国家码）</label><input id="mobile" type="tel" autocomplete="tel" maxlength="32" placeholder="请输入手机号"><div class="row"><button id="sendCode">获取验证码</button></div><label for="code">短信验证码</label><input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="8" placeholder="请输入验证码"><button id="verifyCode" class="primary">登录并同步到电视</button>
</details><p id="loginNote" role="status"></p><details id="qrDetails"><summary>改用红果 App 扫码授权</summary><p class="muted">用红果 App「我的 → 扫一扫」扫描此码；同一手机可尝试从相册识别。授权后返回本页，电视会自动同步。</p><button id="getQr">显示授权二维码</button><div id="qrBox" hidden><p><img id="qrImage" alt="红果登录授权二维码"></p><p id="qrNote" class="muted"></p></div></details></div>
<button id="switchAccount" hidden>换个账户登录</button><details><summary>连接设置</summary><p class="muted">同一局域网可用。电视退出应用后连接失效，需要重新扫码。</p><button id="disconnect">断开所有手机</button></details></section>
<div id="listArea"><div class="row"><button id="backList" hidden>返回原列表</button><button id="refresh">刷新列表</button><span id="page" class="muted"></span><button id="retry" hidden>重试</button></div><div id="rows"></div><div class="row" id="pagination"><button id="prev">上一页</button><button id="next">下一页</button></div></div>
<script nonce="$nonce">
(function () {
'use strict';
var tab='browse', browseMode='search', mode='search', page=1, keyword='', busy=false, commandBusy=false, stopped=false, polling=false;
var timer,listTimer,view=0,lastData=null,shownContext=null,returnList=null,related=false;
var revision=null,accountScope=null,refreshNeeded=false,accountChanged=false,retryAction=null,signedIn=false,playing=false;
var libraryKind='favorites',filterOpen={},filterLimits={},filterBatch=8;
var selection={},cursors=[null],groups=[],suggestTimer,suggestVersion=0,suggestPending=false;
var loginBusy=false,qrPending=false,qrDeadline=0,qrTimer,qrPolling=false,resendAt=0,countdown,loginSubmitted=false,changingAccount=false,submittedScope=null;
var by=function(id){return document.getElementById(id)};
function note(text){by('notice').textContent=text||''}
function api(data){
  var controller=typeof AbortController==='undefined'?null:new AbortController();
  var timeout=controller?setTimeout(function(){controller.abort()},25000):null;
  return fetch(location.pathname+'/api',{method:'POST',headers:{'Content-Type':'application/json','X-Control-Request':'1'},body:JSON.stringify(data),cache:'no-store',signal:controller?controller.signal:undefined})
    .then(function(r){return r.json().then(function(v){if(!r.ok||v.error)throw Error(v.error||'请求未完成');return v})})
    .then(function(v){clearTimeout(timeout);return v},function(e){clearTimeout(timeout);throw Error(e.name==='AbortError'?'连接超时，请重试':e.message)});
}
function button(text,action){
  var b=document.createElement('button');b.textContent=text;
  b.onclick=function(){if(busy)return;busy=true;b.disabled=true;
    Promise.resolve().then(action).catch(function(e){note(e.message)}).then(function(){
      busy=false;b.disabled=b.getAttribute('data-pending')==='true';
    });
  };return b;
}
function command(action,extra){return api(Object.assign({action:action},extra||{})).then(function(v){note(v.message);return v})}
function nav(){
 by('searchMode').setAttribute('aria-pressed',String(browseMode==='search'));by('discoverMode').setAttribute('aria-pressed',String(browseMode==='discover'));
 by('filters').hidden=tab!=='browse'||browseMode!=='discover';
}
function cloudList(){return mode==='online'}
function gate(){var gated=tab==='library'&&cloudList()&&!signedIn;by('loginGate').hidden=!gated;by('listArea').hidden=gated;return gated}
function changeTab(next){
 tab=next;Array.prototype.forEach.call(document.querySelectorAll('[data-tab]'),function(b){var selected=b.getAttribute('data-tab')===tab;b.setAttribute('aria-selected',String(selected));by('panel-'+b.getAttribute('data-tab')).hidden=!selected});
 clearTimeout(listTimer);clearTimeout(qrTimer);hideSuggestions();note('');
 if(tab==='browse'||tab==='library'){
  by(tab==='browse'?'browseHost':'libraryHost').appendChild(by('listArea'));
  var nextMode=tab==='browse'?browseMode:libraryKind;
  if(mode!==nextMode){view++;mode=nextMode;page=1;related=false;returnList=null;shownContext=null;render({items:[]});}
  nav();if(!gate()&&!related){if(mode!=='search'||keyword)load(true);else emptySearch()}
 }else{by('listArea').hidden=true;if(tab==='account'){if(!signedIn&&!qrPending&&!loginBusy&&!loginSubmitted)prepareQr(false);else pollQr()}}
}
function emptySearch(){by('rows').textContent='输入剧名，或切换到「按标签找剧」。';by('rows').className='empty';by('pagination').hidden=true;by('page').textContent='';by('refresh').hidden=true}
function hideSuggestions(){clearTimeout(suggestTimer);suggestVersion++;by('suggestions').hidden=true;by('suggestions').textContent=''}
function searchFor(value){if(!value.trim())return;hideSuggestions();keyword=value.trim();by('query').value=keyword;browseMode='search';mode='search';page=1;nav();load(false)}
function suggest(){
 var value=by('query').value.trim(),token=++suggestVersion;if(!value||tab!=='browse'){hideSuggestions();return}
 if(suggestPending){suggestTimer=setTimeout(suggest,350);return}suggestPending=true;
 api({action:'suggest',query:value}).then(function(v){if(token!==suggestVersion||tab!=='browse')return;by('suggestions').textContent='';(v.items||[]).slice(0,8).forEach(function(t){var b=document.createElement('button');b.textContent=t;b.onclick=function(){searchFor(t)};by('suggestions').appendChild(b)});by('suggestions').hidden=!by('suggestions').children.length}).catch(function(){}).then(function(){suggestPending=false});
}
function renderFilters(){
 by('filters').textContent='';
 groups.forEach(function(g,index){
  var collapsible=index>=2,section=document.createElement(collapsible?'details':'section');section.className='filter';section.setAttribute('data-filter',g.type);
  if(collapsible){section.open=filterOpen[g.type]!==false;section.ontoggle=function(){if(document.documentElement.contains(section))filterOpen[g.type]=section.open}}
  var title=document.createElement(collapsible?'summary':'h3');
  var selected=(g.options||[]).find(function(o){return o.id===selection[g.type]});
  title.textContent=g.label+(selected?' · '+selected.label:'');section.appendChild(title);
  var row=document.createElement('div');row.className='chips';
  var options=[{id:'',label:'全部'}].concat(g.options||[]),selectedIndex=options.findIndex(function(o){return o.id===selection[g.type]});
  var limit=Math.max(filterLimits[g.type]||filterBatch,selectedIndex+1);
  options.slice(0,limit).forEach(function(o){
   var b=document.createElement('button');b.textContent=o.label;b.setAttribute('aria-pressed',String((selection[g.type]||'')===o.id));
   b.onclick=function(){if(busy)return;if(g.type==='genre')selection={};if(o.id)selection[g.type]=o.id;else delete selection[g.type];page=1;cursors=[null];load(false)};row.appendChild(b);
  });
  section.appendChild(row);
  if(limit<options.length){var more=document.createElement('button');more.className='filter-more';more.textContent='展示更多';more.setAttribute('aria-label',g.label+'：展示更多');more.onclick=function(){filterLimits[g.type]=limit+filterBatch;renderFilters();var target=Array.prototype.find.call(by('filters').children,function(el){return el.getAttribute('data-filter')===g.type});if(target){var next=target.querySelector('.filter-more')||target.querySelector('.chips button:last-child');if(next)next.focus()}};section.appendChild(more)}
  by('filters').appendChild(section);
 });
}

var coverQueue=[],coverActive=false,coverObserver=null,coverEpoch=0;
function nextCover(){
 if(coverActive||!coverQueue.length||stopped)return;
 if(busy||commandBusy){setTimeout(nextCover,150);return}
 var job=coverQueue.shift();
 if(job.epoch!==coverEpoch||!document.documentElement.contains(job.poster)){nextCover();return}
 coverActive=true;
 api({action:'cover',id:job.item.id,scope:job.item.scope}).then(function(v){
  if(job.epoch!==coverEpoch||!document.documentElement.contains(job.poster))return;
  job.poster.textContent='';
  if(!v.image){job.poster.textContent='暂无封面';return}
  if(!/^data:image\/jpeg;base64,/.test(v.image))throw Error('封面格式异常');
  var image=document.createElement('img');image.alt=job.item.title+' 封面';image.decoding='async';
  image.onerror=function(){coverRetry(job)};image.src=v.image;job.poster.appendChild(image);
 }).catch(function(){if(job.epoch===coverEpoch)coverRetry(job)}).then(function(){coverActive=false;setTimeout(nextCover,120)});
}
function coverRetry(job){
 job.poster.textContent='';var retry=document.createElement('button');retry.textContent='重试封面';
 retry.onclick=function(){job.poster.textContent='加载封面…';coverQueue.push(job);nextCover()};job.poster.appendChild(retry);
}
function queueCover(item,poster){
 var job={item:item,poster:poster,epoch:coverEpoch};poster.textContent='加载封面…';
 if(coverObserver){poster._coverJob=job;coverObserver.observe(poster)}else coverQueue.push(job);
}
function favoriteLabel(item){return item.favoritePending?'收藏待同步':item.favorite?'取消账户收藏':'加入账户收藏'}
function render(data){
  coverEpoch++;coverQueue=[];if(coverObserver)coverObserver.disconnect();
  coverObserver=typeof IntersectionObserver==='undefined'?null:new IntersectionObserver(function(entries){
   entries.forEach(function(entry){if(entry.isIntersecting){coverObserver.unobserve(entry.target);coverQueue.push(entry.target._coverJob)}});nextCover();
  },{rootMargin:'120px'});
  lastData=data;by('rows').textContent='';by('rows').className='';by('pagination').hidden=false;by('refresh').hidden=false;
  var source=related?'related':mode;
  (data.items||[]).forEach(function(item){
    var card=document.createElement('article');card.className='card';
    var head=document.createElement('div');head.className='series-head';
    var poster=document.createElement('div');poster.className='poster';poster.textContent='暂无封面';
    queueCover(item,poster);
    setTimeout(nextCover,0);
    head.appendChild(poster);var info=document.createElement('div');info.className='series-info';
    var h=document.createElement('h3');h.textContent=item.title;info.appendChild(h);
    var p=document.createElement('p');p.className='muted';p.textContent=item.badge||'';info.appendChild(p);head.appendChild(info);card.appendChild(head);
    var actions=document.createElement('div');actions.className='row';
    if(item.playable!==false){
      actions.appendChild(button('电视播放',function(){return command('play',{id:item.id,source:source,scope:item.scope}).then(function(){changeTab('player');poll()})}));
      actions.appendChild(button('相关作品',function(){
        return api({action:'related',id:item.id}).then(function(v){
          clearTimeout(listTimer);view++;
          if(!related)returnList={data:lastData,context:shownContext};
          related=true;render(v);
        });
      }));
      var favorite=button(favoriteLabel(item),function(){
        if(!signedIn){changeTab('account');return}var add=!item.favorite;
        if(!add&&!confirm('从账户收藏中移除《'+item.title+'》？'))return;
        favorite.textContent='正在更新…';favorite.setAttribute('data-pending','true');
        return command('favorite',{id:item.id,add:add,scope:item.scope}).catch(function(e){favorite.textContent=favoriteLabel(item);favorite.setAttribute('data-pending','false');throw e});
      });
      favorite.setAttribute('data-favorite',item.id);favorite.setAttribute('data-pending',String(!!item.favoritePending));favorite.disabled=!!item.favoritePending;
      favorite.update=function(state){item.favorite=state.favorite;item.favoritePending=state.pending;favorite.textContent=favoriteLabel(item);favorite.disabled=!!state.pending||!!state.loading;favorite.setAttribute('data-pending',String(favorite.disabled))};
      actions.appendChild(favorite);
    }else if(item.reservable){
      var reserve=button(item.reservationLabel||'预约',function(){
        if(!signedIn){changeTab('account');return}reserve.textContent='预约中…';reserve.setAttribute('data-pending','true');
        return command('reserve',{id:item.id,scope:item.scope}).catch(function(e){reserve.textContent='预约';reserve.setAttribute('data-pending','false');throw e});
      });
      reserve.setAttribute('data-reserve',item.id);reserve.disabled=['已预约','已提交','预约中…'].indexOf(item.reservationLabel)>=0;
      reserve.setAttribute('data-pending',String(reserve.disabled));actions.appendChild(reserve);
    }
    card.appendChild(actions);by('rows').appendChild(card);
  });
  by('backList').hidden=!related;by('prev').disabled=related||page<=1;by('next').disabled=related||!data.hasMore;
  by('page').textContent=related?'相关作品':'第 '+page+' 页';
  note(data.message||((data.items||[]).length?'':'暂无记录'));
}
function context(){return {mode:mode,page:page,keyword:keyword,selection:Object.assign({},selection)}}
function restore(c){if(c){mode=c.mode;page=c.page;keyword=c.keyword;selection=c.selection||{};if(mode==='search'||mode==='discover')browseMode=mode;nav();renderFilters()}}
function load(refresh){
  if(busy){refreshNeeded=true;return}
  if(gate())return;
  if(mode==='search'&&!keyword){note('请输入剧名');by('query').focus();return}
  busy=true;clearTimeout(listTimer);by('retry').hidden=true;
  var current=++view, requested=context();note('正在读取…');
  api({action:mode==='search'?'search':mode==='discover'?'discover':'list',kind:mode,page:page,query:keyword,refresh:!!refresh,filters:selection,cursor:mode==='discover'?cursors[page-1]:undefined})
    .then(function(v){if(current!==view)return;related=false;returnList=null;shownContext=requested;if(mode==='discover'){if(v.groups&&v.groups.length)groups=v.groups;cursors[page]=v.cursor||null;renderFilters()}render(v);
      if(v.loading&&!document.hidden)listTimer=setTimeout(function(){if(current===view)load(false)},1500);
    }).catch(function(e){
      if(current!==view)return;restore(shownContext);retryAction=function(){restore(requested);load(refresh)};
      by('retry').hidden=false;note(e.message);
    }).then(function(){busy=false});
}
by('retry').onclick=function(){if(!busy&&retryAction)retryAction()};
by('backList').onclick=function(){if(busy||!returnList)return;var previous=returnList;related=false;returnList=null;restore(previous.context);render(previous.data);load(false)};
Array.prototype.forEach.call(document.querySelectorAll('[data-tab]'),function(b){b.onclick=function(){changeTab(b.getAttribute('data-tab'))};b.onkeydown=function(e){if(e.key==='ArrowRight'||e.key==='ArrowLeft'){var tabs=Array.prototype.slice.call(document.querySelectorAll('[data-tab]')),i=tabs.indexOf(b);tabs[(i+(e.key==='ArrowRight'?1:3))%4].focus();tabs[(i+(e.key==='ArrowRight'?1:3))%4].click();e.preventDefault()}}});
Array.prototype.forEach.call(document.querySelectorAll('[data-library]'),function(b){
 b.onclick=function(){libraryKind=b.getAttribute('data-library');Array.prototype.forEach.call(document.querySelectorAll('[data-library]'),function(t){t.setAttribute('aria-selected',String(t===b))});by('libraryHost').setAttribute('aria-labelledby',b.id);changeTab('library')};
 b.onkeydown=function(e){if(e.key==='ArrowRight'||e.key==='ArrowLeft'){var tabs=Array.prototype.slice.call(document.querySelectorAll('[data-library]')),i=tabs.indexOf(b),next=tabs[(i+(e.key==='ArrowRight'?1:3))%4];next.focus();next.click();e.preventDefault()}};
});
by('goLogin').onclick=function(){changeTab('account')};
by('searchMode').onclick=function(){browseMode='search';changeTab('browse')};
by('discoverMode').onclick=function(){browseMode='discover';changeTab('browse')};
by('search').onclick=function(){searchFor(by('query').value)};
by('query').onkeydown=function(e){if(e.key==='Enter')by('search').click();if(e.key==='Escape')hideSuggestions()};
by('query').oninput=function(){hideSuggestions();suggestTimer=setTimeout(suggest,350)};
by('refresh').onclick=function(){load(true)};
by('prev').onclick=function(){if(!busy&&page>1){page--;load(false)}};
by('next').onclick=function(){if(!busy){page++;load(false)}};
function send(action,extra){if(commandBusy)return;commandBusy=true;command(action,extra).then(function(){if(action==='pause'||action==='resume'){playing=action==='resume';by('togglePlay').textContent=playing?'暂停':'继续'}}).catch(function(e){note(e.message)}).then(function(){commandBusy=false})}
Array.prototype.forEach.call(document.querySelectorAll('[data-cmd]'),function(b){b.onclick=function(){send(b.getAttribute('data-cmd'))}});
by('togglePlay').onclick=function(){send(playing?'pause':'resume')};
by('speed').onchange=function(){send('speed',{value:Number(this.value)})};
by('quality').onchange=function(){send('quality',{value:Number(this.value)})};
by('jump').onclick=function(){send('episode',{value:Number(by('episode').value)})};

by('disconnect').onclick=function(){if(confirm('断开所有手机？重新连接需要在电视上重新打开控制台。'))command('disconnect').then(function(){stopped=true;clearTimeout(timer);clearTimeout(listTimer);clearTimeout(qrTimer);note('已断开，请在电视上重新连接')}).catch(function(){stopped=true;clearTimeout(timer);clearTimeout(listTimer);clearTimeout(qrTimer);note('连接已关闭')})};
function loginNote(t){by('loginNote').textContent=t||''}
function loginAction(action,extra){if(loginBusy)return Promise.resolve(null);loginBusy=true;by('sendCode').disabled=true;by('verifyCode').disabled=true;by('getQr').disabled=true;by('prepareApp').disabled=true;return api(Object.assign({action:action},extra||{})).then(function(v){loginNote(v.message);return v}).catch(function(e){loginNote(e.message);return null}).then(function(v){loginBusy=false;by('verifyCode').disabled=false;by('getQr').disabled=false;by('prepareApp').disabled=false;updateCountdown();return v})}
function updateCountdown(){clearTimeout(countdown);var left=Math.max(0,Math.ceil((resendAt-Date.now())/1000));by('sendCode').textContent=left?left+' 秒后重发':'获取验证码';by('sendCode').disabled=loginBusy||left>0;if(left&&!document.hidden)countdown=setTimeout(updateCountdown,1000)}
by('sendCode').onclick=function(){qrPending=false;clearTimeout(qrTimer);by('openApp').hidden=true;by('prepareApp').hidden=false;var mobile=by('mobile').value.trim();if(!mobile){loginNote('请输入手机号');return}loginAction('loginSend',{mobile:mobile}).then(function(v){if(v){resendAt=Date.now()+v.retrySeconds*1000;updateCountdown();by('code').focus()}})};
by('verifyCode').onclick=function(){var code=by('code').value.trim();if(!/^[0-9]{4,8}$/.test(code)){loginNote('请输入 4–8 位短信验证码');return}loginAction('loginVerify',{code:code}).then(function(v){if(v&&v.submitted){loginSubmitted=true;submittedScope=accountScope;qrPending=false;by('code').value='';by('mobile').value='';poll()}})};
function prepareQr(showImage){qrPending=false;clearTimeout(qrTimer);by('openApp').hidden=true;return loginAction('loginQr').then(function(v){if(!v)return;by('qrImage').src=v.image;by('qrBox').hidden=!showImage;qrDeadline=Date.now()+v.expiresIn*1000;qrPending=true;by('qrNote').textContent='等待红果 App 授权，返回本页后自动同步';if(v.appUrl&&v.appUrl.indexOf('dragon8662://webview?url=')===0){by('openApp').href=v.appUrl;by('openApp').hidden=false;by('prepareApp').hidden=true;by('appHint').textContent='请先确认手机红果 App 已登录，再点击授权。确认后返回此页，电视自动同步。'}else{by('appHint').textContent='暂未取得 App 授权入口，可使用验证码或扫码登录。'}pollQr()})}
by('prepareApp').onclick=function(){prepareQr(false)};
by('getQr').onclick=function(){prepareQr(true)};
by('openApp').onclick=function(e){if(Date.now()>=qrDeadline){e.preventDefault();by('openApp').hidden=true;by('prepareApp').hidden=false;loginNote('授权已过期，请重新发起');return}by('appHint').textContent='在红果 App 确认后返回此页。未能打开时，请用系统浏览器重试，或改用验证码登录。'};
function pollQr(){clearTimeout(qrTimer);if(!qrPending||qrPolling||stopped||document.hidden||tab!=='account')return;qrPolling=true;api({action:'loginPoll'}).then(function(v){if(v.pending&&Date.now()>=qrDeadline){qrPending=false;by('openApp').hidden=true;by('prepareApp').hidden=false;loginNote('授权已过期，请重新获取');return}if(v.submitted){qrPending=false;by('openApp').hidden=true;loginSubmitted=true;submittedScope=accountScope;loginNote(v.message);by('qrBox').hidden=true;by('qrImage').removeAttribute('src');poll()}}).catch(function(e){qrPending=false;by('openApp').hidden=true;by('prepareApp').hidden=false;by('qrNote').textContent=e.message;loginNote(e.message)}).then(function(){qrPolling=false;if(qrPending)qrTimer=setTimeout(pollQr,2500)})}
by('switchAccount').onclick=function(){changingAccount=true;prepareQr(false);by('loginPanel').hidden=false;by('switchAccount').hidden=true;loginNote('登录后将替换这台电视的当前账户')};
function time(ms){var n=Math.floor(Math.max(0,ms)/1000);return Math.floor(n/60)+':'+('0'+n%60).slice(-2)}
function poll(){
  if(stopped||document.hidden||polling)return;polling=true;
  api({action:'status'}).then(function(s){
    signedIn=!!s.signedIn;
    if(s.reservation)Array.prototype.forEach.call(document.querySelectorAll('[data-reserve]'),function(b){if(b.getAttribute('data-reserve')===s.reservation.id){b.textContent=s.reservation.label;b.disabled=['已预约','已提交','预约中…'].indexOf(s.reservation.label)>=0;b.setAttribute('data-pending',String(b.disabled))}});
    if(s.favorite)Array.prototype.forEach.call(document.querySelectorAll('[data-favorite]'),function(b){if(b.getAttribute('data-favorite')===s.favorite.id)b.update(s.favorite)});
    if(s.scope!==undefined){if(accountScope!==null&&accountScope!==s.scope){accountChanged=true;view++;page=1;related=false;returnList=null;shownContext=null;render({items:[]});note('账户已切换，正在更新片单…')}accountScope=s.scope}
    if(s.catalogRevision!==undefined){if(revision!==null&&revision!==s.catalogRevision)refreshNeeded=true;revision=s.catalogRevision}
    if((tab==='browse'||tab==='library')&&!busy&&!related&&(refreshNeeded||accountChanged)&&(mode!=='search'||keyword)){refreshNeeded=false;accountChanged=false;load(false)}
    by('playing').textContent=s.title||'电视未在剧集播放页';
    by('position').textContent=s.active?('第 '+s.episode+' / '+s.episodes+' 集 · '+time(s.position)+' / '+time(s.duration)+' · '+(s.playing?'播放中':'已暂停 / 准备中')):'选择剧目后点击「电视播放」';
    signedIn=!!s.signedIn;playing=!!s.playing;by('togglePlay').textContent=playing?'暂停':'继续';by('connection').textContent=s.active?'电视正在播放 · '+s.title:'已连接电视';by('accountLabel').textContent=s.account||'未登录';by('accountStatus').textContent=s.accountStatus===s.account?'':s.accountStatus||'';if(loginSubmitted&&signedIn&&s.scope!==submittedScope){loginSubmitted=false;changingAccount=false;by('loginPanel').hidden=true;by('switchAccount').hidden=false;loginNote('');note('已登录并同步到电视')}else if(signedIn&&!changingAccount&&!loginSubmitted){by('loginPanel').hidden=true;by('switchAccount').hidden=false}if(!signedIn){by('loginPanel').hidden=false;by('switchAccount').hidden=true}if(tab==='library')gate();
    if(!commandBusy){by('speed').value=String(s.speed);by('quality').value=String(s.quality)}
  }).catch(function(){by('connection').textContent='连接中断，请检查电视或重新扫码'}).then(function(){polling=false;if(!stopped&&!document.hidden)timer=setTimeout(poll,3000)});
}
document.addEventListener('visibilitychange',function(){clearTimeout(timer);clearTimeout(listTimer);clearTimeout(qrTimer);clearTimeout(countdown);hideSuggestions();if(!document.hidden){poll();pollQr();updateCountdown();if((tab==='browse'||tab==='library')&&mode!=='search'&&!related)load(false)}});by('browseHost').appendChild(by('listArea'));emptySearch();nav();poll();
})();
</script></body></html>"""
}
