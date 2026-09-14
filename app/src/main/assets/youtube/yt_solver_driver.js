/* P11-101:WebView 内 YouTube s/n decipher 驱动。
 * 依赖加载顺序(Kotlin 侧依次 eval):meriyah.min.js(UMD→window.meriyah)
 *   → astring.min.js(UMD→window.astring)→ yt.solver.core.js(var jsc,来自 yt-dlp
 *   yt.solver.core.js,Unlicense,自动生成自 https://github.com/yt-dlp/ejs)→ 本驱动。
 * 协议:__ytSolveRun(url, n[], sig[]) 内部 fetch base.js(同源 www.youtube.com)→
 *   jsc({type:'player',player,requests}) → __ytSolveResult(Kotlin 轮询)。
 * solver 机制:AST 结构匹配 IIFE 内的 URL 类构造函数(含 'alr','yes' 调用标记)→
 *   实例化 URL 对象 → url.set("n", n) / 传入 sig → **调用原型自有方法触发 transform**
 *   → 读回 url.get("n")/url.get("s")。不依赖 player hash→nClass 配置(alpha.32 证伪的
 *   旧 URL 类法的两个缺陷:config 覆盖 + 缺 transform 触发,均由 solver 修复)。 */
window.__ytSolveResult = null;
window.__ytSolveRun = async function (playerJsUrl, nChallenges, sigChallenges) {
  try {
    if (typeof jsc !== 'function') {
      window.__ytSolveResult = JSON.stringify({ type: 'error', error: 'solver core not loaded' });
      return;
    }
    const resp = await fetch(playerJsUrl, { credentials: 'omit' });
    if (!resp.ok) {
      window.__ytSolveResult = JSON.stringify({ type: 'error', error: 'base.js HTTP ' + resp.status });
      return;
    }
    const playerJs = await resp.text();
    const out = jsc({
      type: 'player',
      player: playerJs,
      requests: [
        { type: 'n', challenges: nChallenges || [] },
        { type: 'sig', challenges: sigChallenges || [] },
      ],
    });
    window.__ytSolveResult = JSON.stringify(out);
  } catch (e) {
    window.__ytSolveResult = JSON.stringify({ type: 'error', error: String((e && e.message) || e) });
  }
};
window.__ytSolveLoaded = true;