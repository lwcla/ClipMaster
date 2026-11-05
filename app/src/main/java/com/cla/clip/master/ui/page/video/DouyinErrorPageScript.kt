package com.cla.clip.master.ui.page.video

/** JS 只返回布尔值，不向原生层带出网页正文或资源信息。 */
internal const val DOUYIN_ERROR_PAGE_JS = """
(function() {
  try {
    // 页面来源再次校验，避免原生排队期间导航到其他站点。
    var hosts = ['iesdouyin.com', 'www.iesdouyin.com', 'douyin.com', 'www.douyin.com', 'm.douyin.com'];
    if (hosts.indexOf(location.hostname.toLowerCase()) < 0 ||
        !/^\/share\/video\/[0-9]+\/?$/.test(location.pathname) || document.readyState !== 'complete') return false;

    // 任何已有媒体源都保留请求探测机会，不以错误占位覆盖正常播放器。
    var videos = document.querySelectorAll('video');
    // 按当前文档的即时节点顺序逐个检查，不将下标作为资源身份。
    for (var i = 0; i < videos.length; i++) {
      // 当前资源、src 属性和 source 子节点均可能承载尚在加载的有效媒体。
      var video = videos[i];
      if ((video.currentSrc || video.getAttribute('src') || '').trim()) return false;
      // source 可能在播放器初始化前下发，不能只检查 currentSrc。
      var sources = video.querySelectorAll('source[src]');
      // 任一候选 source 都足以保留媒体提取机会。
      for (var j = 0; j < sources.length; j++) {
        if ((sources[j].getAttribute('src') || '').trim()) return false;
      }
    }

    // 检查节点及祖先的 DOM 可见性；原生 WebView 的透明模式不影响这里的判断。
    function visible(node) {
      if (!node || node.getClientRects().length === 0) return false;
      // 节点需实际占用布局；隐藏历史错误节点不能作为终态。
      var rect = node.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return false;
      // 从当前节点向根节点检查，避免只检查自身样式漏掉隐藏祖先。
      for (var parent = node; parent; parent = parent.parentElement) {
        // 祖先隐藏、辅助技术隐藏和透明占位都按未确认处理。
        var style = getComputedStyle(parent);
        if (parent.hidden || parent.getAttribute('aria-hidden') === 'true' ||
            style.display === 'none' || style.visibility === 'hidden' ||
            style.visibility === 'collapse' || Number(style.opacity) === 0) return false;
      }
      return true;
    }

    // 仅在已核验的专用容器内做完整文案匹配，不扫描正文或评论。
    function matches(node, expected) {
      return visible(node) && (node.innerText || '').replace(/\s+/g, '') === expected;
    }
    // 容器类名来自公开分享组件；改版导致不匹配时回到原超时路径。
    var containers = document.querySelectorAll('.special-case-wrap .special-case-container');
    // 每个组件独立判定，不跨容器拼接标题与按钮。
    for (var k = 0; k < containers.length; k++) {
      // 三个节点必须同属一个当前可见的错误组件，普通打开按钮不能触发。
      var container = containers[k];
      if (visible(container) &&
          matches(container.querySelector('.special-case-title'), '抱歉出错了') &&
          matches(container.querySelector('.special-case-subtitle'), '请尝试在抖音内观看') &&
          matches(container.querySelector('.special-case-btn'), '打开抖音')) return true;
    }
  } catch (error) {
    // 脚本异常属于未知状态，不携带外部异常文本、不直接报告提取失败。
  }
  return false;
})();
"""
