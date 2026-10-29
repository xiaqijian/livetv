/* 电视直播 - Windows 版前端 */
(function () {
  "use strict";

  var ALL_GROUP = "__all__";
  var state = {
    channels: [],
    groups: [],
    meta: {},
    activeGroup: ALL_GROUP,
    playingIndex: -1,
    hls: null
  };

  var el = {
    groupList: document.getElementById("groupList"),
    groupTitle: document.getElementById("groupTitle"),
    channelCount: document.getElementById("channelCount"),
    grid: document.getElementById("channelGrid"),
    empty: document.getElementById("emptyState"),
    sourceBadge: document.getElementById("sourceBadge"),
    sourceBtn: document.getElementById("sourceBtn"),
    playerLayer: document.getElementById("playerLayer"),
    video: document.getElementById("video"),
    playerName: document.getElementById("playerName"),
    playerStatus: document.getElementById("playerStatus"),
    closePlayerBtn: document.getElementById("closePlayerBtn"),
    fullscreenBtn: document.getElementById("fullscreenBtn"),
    playerError: document.getElementById("playerError"),
    playerErrorText: document.getElementById("playerErrorText"),
    nextChannelBtn: document.getElementById("nextChannelBtn"),
    retryBtn: document.getElementById("retryBtn"),
    closePlayerBtn2: document.getElementById("closePlayerBtn2"),
    modal: document.getElementById("sourceModal"),
    sourceInput: document.getElementById("sourceInput"),
    sourceError: document.getElementById("sourceError"),
    saveSourceBtn: document.getElementById("saveSourceBtn"),
    cancelSourceBtn: document.getElementById("cancelSourceBtn"),
    resetSourceBtn: document.getElementById("resetSourceBtn"),
    toast: document.getElementById("toast")
  };

  /* ---------- 工具 ---------- */

  var toastTimer = null;
  function toast(message, isError) {
    el.toast.textContent = message;
    el.toast.classList.toggle("err", !!isError);
    el.toast.classList.remove("hidden");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.toast.classList.add("hidden");
    }, 4000);
  }

  function proxyUrl(target) {
    return "/api/proxy?u=" + encodeURIComponent(target);
  }

  function isHlsUrl(url) {
    var lower = url.toLowerCase();
    return lower.indexOf(".m3u8") !== -1 || lower.indexOf("m3u8") !== -1;
  }

  function protocolLabel(url) {
    var match = /^([a-z0-9+.-]+):\/\//i.exec(url || "");
    if (!match) return "unknown";
    var scheme = match[1].toLowerCase();
    if (scheme === "http" || scheme === "https") {
      return isHlsUrl(url) ? "hls" : "http";
    }
    return scheme;
  }

  function request(url, options) {
    return fetch(url, options).then(function (response) {
      return response
        .json()
        .catch(function () {
          return { ok: false, error: "服务返回了无法识别的内容" };
        })
        .then(function (data) {
          if (!response.ok || data.ok === false) {
            throw new Error(data.error || "请求失败（" + response.status + "）");
          }
          return data;
        });
    });
  }

  /* ---------- 列表渲染 ---------- */

  function renderGroups() {
    var total = state.channels.length;
    var groups = [{ name: "全部频道", count: total, key: ALL_GROUP }].concat(
      state.groups.map(function (item) {
        return { name: item.name, count: item.count, key: item.name };
      })
    );

    el.groupList.textContent = "";
    groups.forEach(function (item) {
      var li = document.createElement("li");
      if (item.key === state.activeGroup) li.className = "active";
      var label = document.createElement("span");
      label.textContent = item.name;
      var num = document.createElement("span");
      num.className = "n";
      num.textContent = item.count;
      li.appendChild(label);
      li.appendChild(num);
      li.addEventListener("click", function () {
        state.activeGroup = item.key;
        renderGroups();
        renderChannels();
      });
      el.groupList.appendChild(li);
    });
  }

  function visibleChannels() {
    if (state.activeGroup === ALL_GROUP) return state.channels;
    return state.channels.filter(function (channel) {
      return channel.group === state.activeGroup;
    });
  }

  function renderChannels() {
    var list = visibleChannels();
    el.groupTitle.textContent =
      state.activeGroup === ALL_GROUP ? "全部频道" : state.activeGroup;
    el.channelCount.textContent = list.length + " 个频道";

    el.grid.textContent = "";
    if (!list.length) {
      el.empty.classList.remove("hidden");
      el.empty.textContent = "这个分组下没有频道";
      return;
    }
    el.empty.classList.add("hidden");

    var fragment = document.createDocumentFragment();
    list.forEach(function (channel) {
      var card = document.createElement("div");
      card.className = "channel";
      card.title = channel.name + "\n" + channel.url;

      var logo = null;
      if (channel.logo && /^https?:\/\//i.test(channel.logo)) {
        logo = document.createElement("img");
        logo.className = "logo";
        logo.loading = "lazy";
        logo.referrerPolicy = "no-referrer";
        logo.src = channel.logo;
        logo.addEventListener("error", function () {
          var fallback = document.createElement("div");
          fallback.className = "logo-fallback";
          fallback.textContent = (channel.name || "?").slice(0, 1);
          if (logo.parentNode) logo.parentNode.replaceChild(fallback, logo);
        });
      } else {
        logo = document.createElement("div");
        logo.className = "logo-fallback";
        logo.textContent = (channel.name || "?").slice(0, 1);
      }

      var meta = document.createElement("div");
      meta.className = "meta";
      var name = document.createElement("div");
      name.className = "name";
      name.textContent = channel.name;
      var proto = document.createElement("div");
      proto.className = "proto";
      proto.textContent = protocolLabel(channel.url);
      meta.appendChild(name);
      meta.appendChild(proto);

      card.appendChild(logo);
      card.appendChild(meta);
      card.addEventListener("click", function () {
        playChannel(channel, card);
      });
      fragment.appendChild(card);
    });
    el.grid.appendChild(fragment);
  }

  function markPlaying(card) {
    var playing = el.grid.querySelector(".channel.playing");
    if (playing) playing.classList.remove("playing");
    if (card) {
      card.classList.add("playing");
      state.playingIndex = visibleChannels().findIndex(function (channel) {
        return channel.url === card.title.split("\n").pop();
      });
    }
  }

  function setStatus(text, isError) {
    el.playerStatus.textContent = text || "";
    el.playerStatus.classList.toggle("err", !!isError);
  }

  function showPlayerError(text) {
    el.playerErrorText.textContent = text;
    el.playerError.classList.remove("hidden");
  }

  function hidePlayerError() {
    el.playerError.classList.add("hidden");
  }

  /* 把播放器抛出的英文错误翻译成用户能看懂的一句话 */
  function describePlayError(error) {
    var msg = (error && error.message) || "";
    if (/HEVC|H\.?265|M2TS/i.test(msg)) {
      return "这个台是 H.265（HEVC）编码，当前播放内核解不了。换一个台试试，或改用 H.264 的源。";
    }
    if (/连接超时/.test(msg)) {
      return msg + "。这个源太慢或者已经失效了。";
    }
    if (/manifestLoad|manifestParsing|levelLoad/i.test(msg)) {
      return "这个台的播放列表打不开（" + msg + "）。";
    }
    if (/parsing|Append|bufferStalled|fragLoad/i.test(msg)) {
      return "这个台的视频格式内核处理不了（" + msg + "）。";
    }
    if (/network|timeout/i.test(msg)) {
      return "取不到视频数据（" + msg + "）。";
    }
    return msg || "未知错误，换个台试试。";
  }

  /* ---------- 播放 ---------- */

  function destroyPlayer() {
    if (state.hls) {
      try { state.hls.destroy(); } catch (e) { /* ignore */ }
      state.hls = null;
    }
    try {
      el.video.pause();
      el.video.removeAttribute("src");
      el.video.load();
    } catch (e) { /* ignore */ }
  }

  function closePlayer() {
    destroyPlayer();
    hidePlayerError();
    el.playerLayer.classList.add("hidden");
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(function () {});
    }
    markPlaying(null);
  }

  /* 有些源分片很大（实测 5 MB / 52 秒），首帧前要等很久，必须让用户看到在等什么。
     20 秒是折中：慢源给足机会，又不至于让人以为死机。 */
  var FIRST_FRAME_TIMEOUT = 20000;

  function startVideo(video) {
    return video.play().catch(function () {
      // 内核拦截自动播放时，先静音播放，至少把画面推出来
      video.muted = true;
      return video.play().catch(function () {});
    });
  }

  /* 用 hls.js 播放，返回 Promise，成功=拿到的播放列表 */
  function playWithHls(url) {
    return new Promise(function (resolve, reject) {
      if (!window.Hls || !window.Hls.isSupported()) {
        reject(new Error("当前播放内核不支持 HLS"));
        return;
      }
      var hls = new window.Hls({
        maxBufferLength: 30,
        manifestLoadingTimeOut: 20000,
        manifestLoadingMaxRetry: 1,
        fragLoadingTimeOut: 45000,
        fragLoadingMaxRetry: 2
      });
      state.hls = hls;

      var settled = false;
      var startedAt = Date.now();
      var timer = null;
      var ticker = setInterval(function () {
        if (settled) return;
        setStatus("正在连接… 已等待 " + Math.round((Date.now() - startedAt) / 1000) + " 秒");
      }, 1000);

      function settle(ok, error) {
        if (settled) return;
        settled = true;
        clearInterval(ticker);
        clearTimeout(timer);
        el.video.removeEventListener("playing", onPlaying);
        if (ok) {
          resolve();
          return;
        }
        try { hls.destroy(); } catch (e) { /* ignore */ }
        reject(error);
      }

      timer = setTimeout(function () {
        settle(false, new Error(
          "连接超时：" + Math.round((Date.now() - startedAt) / 1000) + " 秒内没收到视频数据"
        ));
      }, FIRST_FRAME_TIMEOUT);

      // 播放列表到手不等于能看：分片可能还要几十秒，必须等真正出画面
      function onPlaying() {
        settle(true);
      }
      el.video.addEventListener("playing", onPlaying);

      hls.on(window.Hls.Events.MANIFEST_PARSED, function () {
        startVideo(el.video);
      });
      hls.on(window.Hls.Events.ERROR, function (_event, data) {
        if (!data || !data.fatal) return;
        var detail = data.details || "HLS 播放失败";
        if (data.reason) detail += "（" + data.reason + "）";
        settle(false, new Error(detail));
      });
      hls.attachMedia(el.video);
      hls.loadSource(url);
    });
  }

  /* 用原生 video 播放（mp4 / 部分 http-flv 直连） */
  function playNative(url) {
    return new Promise(function (resolve, reject) {
      var settled = false;
      var timer = setTimeout(function () {
        if (settled) return;
        settled = true;
        cleanup();
        reject(new Error("连接超时"));
      }, 30000);

      function cleanup() {
        el.video.removeEventListener("loadeddata", onOk);
        el.video.removeEventListener("error", onErr);
      }
      function onOk() {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        cleanup();
        el.video.play().catch(function () {});
        resolve();
      }
      function onErr() {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        cleanup();
        reject(new Error("浏览器无法播放该地址"));
      }

      el.video.addEventListener("loadeddata", onOk);
      el.video.addEventListener("error", onErr);
      el.video.src = url;
    });
  }

  function tryPlay(rawUrl) {
    var target = proxyUrl(rawUrl);
    if (isHlsUrl(rawUrl)) return playWithHls(target);
    return playNative(target).catch(function () {
      // 地址不带 .m3u8 但实际是 HLS 的情况，再试一次 hls.js
      return playWithHls(target);
    });
  }

  async function playChannel(channel, card) {
    destroyPlayer();
    hidePlayerError();
    el.playerLayer.classList.remove("hidden");
    el.playerName.textContent = channel.name;
    setStatus("正在连接…");
    markPlaying(card);
    state.currentChannel = channel;

    var candidates = [channel.url].concat(channel.backups || []).filter(Boolean);
    var lastError = null;
    for (var i = 0; i < candidates.length; i++) {
      try {
        setStatus(i === 0 ? "正在连接…" : "尝试备用线路 " + i + "…");
        await tryPlay(candidates[i]);
        setStatus(isHlsUrl(candidates[i]) ? "HLS 直播中" : "播放中");
        return;
      } catch (error) {
        lastError = error;
      }
    }
    destroyPlayer();
    setStatus("播放失败", true);
    showPlayerError(describePlayError(lastError));
  }

  function stepChannel(delta) {
    var list = visibleChannels();
    if (!list.length) return;
    var current = state.currentChannel;
    var index = current
      ? list.findIndex(function (c) { return c.url === current.url; })
      : -1;
    var next = (index + delta + list.length) % list.length;
    var cards = el.grid.querySelectorAll(".channel");
    playChannel(list[next], cards[next]);
  }

  /* ---------- 数据加载 ---------- */

  function applyPayload(data) {
    state.channels = data.channels || [];
    state.groups = data.groups || [];
    state.meta = data.meta || {};
    if (state.activeGroup !== ALL_GROUP) {
      var exists = state.groups.some(function (g) { return g.name === state.activeGroup; });
      if (!exists) state.activeGroup = ALL_GROUP;
    }
    el.sourceBadge.textContent =
      (state.meta.mode === "custom" ? "自定义源" : "内置源") +
      " · " + state.channels.length + " 台";
    renderGroups();
    renderChannels();
  }

  function loadChannels() {
    return request("/api/channels")
      .then(applyPayload)
      .catch(function (error) {
        el.empty.classList.remove("hidden");
        el.empty.textContent = "频道加载失败：" + error.message;
        toast("频道加载失败：" + error.message, true);
      });
  }

  /* ---------- 订阅源 ---------- */

  function openSourceModal() {
    el.sourceInput.value = state.meta.url || "";
    el.sourceError.textContent = "";
    el.modal.classList.remove("hidden");
    el.sourceInput.focus();
  }

  function closeSourceModal() {
    el.modal.classList.add("hidden");
  }

  function saveSource() {
    var url = el.sourceInput.value.trim();
    if (!url) {
      el.sourceError.textContent = "请输入订阅地址";
      return;
    }
    if (!/^https?:\/\//i.test(url)) {
      el.sourceError.textContent = "地址需以 http:// 或 https:// 开头";
      return;
    }
    el.saveSourceBtn.disabled = true;
    el.saveSourceBtn.textContent = "下载中…";
    el.sourceError.textContent = "";

    request("/api/source", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url: url })
    })
      .then(function (data) {
        applyPayload(data);
        closeSourceModal();
        toast("已切换到自定义源，共 " + state.channels.length + " 台");
      })
      .catch(function (error) {
        el.sourceError.textContent = error.message;
      })
      .finally(function () {
        el.saveSourceBtn.disabled = false;
        el.saveSourceBtn.textContent = "保存并切换";
      });
  }

  function resetSource() {
    el.resetSourceBtn.disabled = true;
    request("/api/source/reset", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    })
      .then(function (data) {
        applyPayload(data);
        el.sourceInput.value = "";
        closeSourceModal();
        toast("已恢复默认源，共 " + state.channels.length + " 台");
      })
      .catch(function (error) {
        el.sourceError.textContent = error.message;
      })
      .finally(function () {
        el.resetSourceBtn.disabled = false;
      });
  }

  /* ---------- 事件绑定 ---------- */

  el.sourceBtn.addEventListener("click", openSourceModal);
  el.cancelSourceBtn.addEventListener("click", closeSourceModal);
  el.saveSourceBtn.addEventListener("click", saveSource);
  el.resetSourceBtn.addEventListener("click", resetSource);
  el.sourceInput.addEventListener("keydown", function (event) {
    if (event.key === "Enter") saveSource();
  });

  el.closePlayerBtn.addEventListener("click", closePlayer);
  el.closePlayerBtn2.addEventListener("click", closePlayer);
  el.nextChannelBtn.addEventListener("click", function () {
    stepChannel(1);
  });
  el.retryBtn.addEventListener("click", function () {
    if (state.currentChannel) playChannel(state.currentChannel, null);
  });
  el.fullscreenBtn.addEventListener("click", function () {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(function () {});
    } else {
      el.video.requestFullscreen().catch(function () {});
    }
  });

  document.addEventListener("keydown", function (event) {
    if (!el.modal.classList.contains("hidden")) {
      if (event.key === "Escape") closeSourceModal();
      return;
    }
    if (el.playerLayer.classList.contains("hidden")) return;
    if (event.key === "Escape") {
      event.preventDefault();
      closePlayer();
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      stepChannel(-1);
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      stepChannel(1);
    }
  });

  window.addEventListener("beforeunload", destroyPlayer);

  loadChannels();
})();
